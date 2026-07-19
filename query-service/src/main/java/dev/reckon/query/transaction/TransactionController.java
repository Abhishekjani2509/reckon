package dev.reckon.query.transaction;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves account transaction history, newest first, one page at a time.
 *
 * <p>Cursor pagination: a page reports a {@code nextCursor}, and the caller passes it back
 * to fetch the next (older) page. The end of history is a null cursor. To decide whether a
 * next page exists, the repository is asked for one row beyond the page size; if it comes
 * back, there is more, and its predecessor's version becomes the cursor.
 */
@RestController
@RequestMapping("/accounts")
public class TransactionController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final TransactionReadRepository repository;

    public TransactionController(TransactionReadRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{accountId}/transactions")
    public TransactionPage transactions(
            @PathVariable String accountId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {

        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);

        // Fetch one extra row to detect a further page without a second query.
        List<TransactionResponse> rows = repository.page(accountId, cursor, pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<TransactionResponse> page = hasMore ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).version() : null;

        return new TransactionPage(page, nextCursor);
    }
}
