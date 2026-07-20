package dev.reckon.command.api;

import dev.reckon.command.application.AccountCommandHandler;
import dev.reckon.command.application.CommandOutcome;
import dev.reckon.command.application.TransferExecution;
import dev.reckon.command.application.TransferOutcome;
import dev.reckon.command.application.TransferSaga;
import dev.reckon.command.domain.account.AccountCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The write side's HTTP surface. Commands only — there is no {@code GET /accounts/{id}}
 * here, and there will not be: serving reads is the query service's job against a
 * projection. Adding a convenience read endpoint to the command service is how CQRS
 * quietly stops being CQRS.
 *
 * <p>Resources are named for the events they record ({@code /deposits},
 * {@code /withdrawals}) rather than for mutations of a balance. Posting a deposit
 * records that a deposit happened; nothing here edits a number.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountCommandHandler handler;
    private final TransferSaga transferSaga;

    public AccountController(AccountCommandHandler handler, TransferSaga transferSaga) {
        this.handler = handler;
        this.transferSaga = transferSaga;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        // Server-assigned id. A client-chosen id would let one client collide with
        // another's account and turn AccountAlreadyExists into a routine error rather
        // than the impossibility it should be.
        String accountId = "acc_" + UUID.randomUUID();

        CommandOutcome outcome = handler.handle(new AccountCommand.OpenAccount(
                accountId, request.owner(), request.currency(), request.idempotencyKey()));
        return respond(outcome, HttpStatus.CREATED);
    }

    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<AccountResponse> deposit(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        CommandOutcome outcome = handler.handle(new AccountCommand.Deposit(
                accountId, request.amountMinor(), request.currency(), request.idempotencyKey()));
        return respond(outcome, HttpStatus.OK);
    }

    @PostMapping("/{accountId}/withdrawals")
    public ResponseEntity<AccountResponse> withdraw(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        CommandOutcome outcome = handler.handle(new AccountCommand.Withdraw(
                accountId, request.amountMinor(), request.currency(), request.idempotencyKey()));
        return respond(outcome, HttpStatus.OK);
    }

    /**
     * Builds the response and marks a replayed (deduplicated) result with the
     * {@code Idempotent-Replayed} header. A fresh application returns the given status; a
     * replay returns 200, since nothing was created on the retry.
     */
    private ResponseEntity<AccountResponse> respond(CommandOutcome outcome, HttpStatus freshStatus) {
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : freshStatus;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(AccountResponse.from(outcome.result()));
    }

    /**
     * Runs a transfer saga from the path account to the request's destination.
     *
     * <p>200 when the transfer completed; 422 when it was compensated — a step failed, the
     * debit was reversed, and the body's {@code failureReason} says why. A compensated
     * transfer is the saga behaving correctly, but the funds did not move, so it is not a
     * 2xx success. Domain rejections before any money moves (overdraft, unknown source,
     * self-transfer) surface through the normal exception handler.
     */
    @PostMapping("/{sourceAccountId}/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @PathVariable String sourceAccountId, @Valid @RequestBody TransferRequest request) {
        TransferExecution execution = transferSaga.execute(new AccountCommand.Transfer(
                sourceAccountId, request.destinationAccountId(),
                request.amountMinor(), request.currency(), request.idempotencyKey()));

        TransferOutcome outcome = execution.outcome();
        // COMPLETED -> 200, COMPENSATED -> 422 (failed but consistent), PENDING -> 202
        // (a retry landed while the original is still in flight; it did not debit again).
        HttpStatus status = switch (outcome.status()) {
            case COMPLETED -> HttpStatus.OK;
            case COMPENSATED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PENDING -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", String.valueOf(execution.replayed()))
                .body(TransferResponse.from(outcome));
    }
}
