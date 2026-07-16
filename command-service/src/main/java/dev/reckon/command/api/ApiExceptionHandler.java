package dev.reckon.command.api;

import dev.reckon.command.application.AccountCommandHandler.ConcurrencyRetriesExhaustedException;
import dev.reckon.command.domain.account.AccountExceptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to HTTP, using RFC 7807 problem details.
 *
 * <p>The status codes carry meaning worth being deliberate about — the difference
 * between "you asked for something impossible" and "try again shortly" is the difference
 * between a client that gives up and one that retries correctly.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    /** No {@code AccountOpened} in the log, so as far as history is concerned it does not exist. */
    @ExceptionHandler(AccountExceptions.AccountNotFound.class)
    ProblemDetail accountNotFound(AccountExceptions.AccountNotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", e.getMessage());
    }

    @ExceptionHandler(AccountExceptions.AccountAlreadyExists.class)
    ProblemDetail accountAlreadyExists(AccountExceptions.AccountAlreadyExists e) {
        return problem(HttpStatus.CONFLICT, "Account already exists", e.getMessage());
    }

    /**
     * 422 rather than 400: the request was perfectly well formed, it was the account's
     * balance that made it impossible. Nothing the client can fix by editing the JSON.
     */
    @ExceptionHandler(AccountExceptions.InsufficientFunds.class)
    ProblemDetail insufficientFunds(AccountExceptions.InsufficientFunds e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds", e.getMessage());
    }

    @ExceptionHandler(AccountExceptions.CurrencyMismatch.class)
    ProblemDetail currencyMismatch(AccountExceptions.CurrencyMismatch e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Currency mismatch", e.getMessage());
    }

    /**
     * 503 with Retry-After semantics rather than 409: nothing is wrong with the command,
     * the account was simply too busy. It may well succeed unchanged a moment later, and
     * the client should be told to try rather than to fix.
     */
    @ExceptionHandler(ConcurrencyRetriesExhaustedException.class)
    ProblemDetail retriesExhausted(ConcurrencyRetriesExhaustedException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Too much contention", e.getMessage());
    }

    /** Raised by Money.requirePositive for a zero or negative amount. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    /** Transfer, until it exists as a saga. */
    @ExceptionHandler(UnsupportedOperationException.class)
    ProblemDetail notImplemented(UnsupportedOperationException e) {
        return problem(HttpStatus.NOT_IMPLEMENTED, "Not implemented", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
