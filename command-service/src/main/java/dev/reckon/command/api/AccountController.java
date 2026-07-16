package dev.reckon.command.api;

import dev.reckon.command.application.AccountCommandHandler;
import dev.reckon.command.domain.account.Account;
import dev.reckon.command.domain.account.AccountCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    public AccountController(AccountCommandHandler handler) {
        this.handler = handler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest request) {
        // Server-assigned id. A client-chosen id would let one client collide with
        // another's account and turn AccountAlreadyExists into a routine error rather
        // than the impossibility it should be.
        String accountId = "acc_" + UUID.randomUUID();

        Account account = handler.handle(new AccountCommand.OpenAccount(
                accountId, request.owner(), request.currency(), request.idempotencyKey()));
        return AccountResponse.from(account);
    }

    @PostMapping("/{accountId}/deposits")
    public AccountResponse deposit(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        Account account = handler.handle(new AccountCommand.Deposit(
                accountId, request.amountMinor(), request.currency(), request.idempotencyKey()));
        return AccountResponse.from(account);
    }

    @PostMapping("/{accountId}/withdrawals")
    public AccountResponse withdraw(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        Account account = handler.handle(new AccountCommand.Withdraw(
                accountId, request.amountMinor(), request.currency(), request.idempotencyKey()));
        return AccountResponse.from(account);
    }
}
