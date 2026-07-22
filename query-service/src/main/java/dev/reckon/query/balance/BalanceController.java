package dev.reckon.query.balance;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Serves account balances: Redis hot, Postgres fallback.
 *
 * <p>The response carries an {@code X-Balance-Source} header naming which store answered,
 * so the hot path is observable without leaking cache mechanics into the response body.
 * A miss in Redis is normal (the key may not be warm yet); Postgres is the durable answer.
 * Only when neither store knows the account is it a 404.
 */
@RestController
@RequestMapping("/accounts")
public class BalanceController {

    private final HotBalanceReader hotBalance;
    private final BalanceReadRepository postgres;

    public BalanceController(HotBalanceReader hotBalance, BalanceReadRepository postgres) {
        this.hotBalance = hotBalance;
        this.postgres = postgres;
    }

    /** All accounts and their current balances, for the dashboard's account list. */
    @GetMapping
    public java.util.List<AccountSummary> list() {
        return postgres.findAll();
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceResponse> balance(@PathVariable String accountId) {
        Optional<BalanceResponse> hot = hotBalance.find(accountId);
        if (hot.isPresent()) {
            return ResponseEntity.ok().header("X-Balance-Source", "redis").body(hot.get());
        }

        BalanceResponse durable = postgres.find(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: " + accountId));
        return ResponseEntity.ok().header("X-Balance-Source", "postgres").body(durable);
    }
}
