package dev.reckon.query.transaction;

import java.util.List;

/**
 * A page of transaction history.
 *
 * @param nextCursor pass as {@code cursor} to fetch the next (older) page; null when this
 *     is the last page. A cursor rather than an offset: it names a fixed row, so paging is
 *     stable even as new transactions arrive, and resuming is O(1) rather than a scan.
 */
public record TransactionPage(
        List<TransactionResponse> transactions,
        Long nextCursor
) {}
