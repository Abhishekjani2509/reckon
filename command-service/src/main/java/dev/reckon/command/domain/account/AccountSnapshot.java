package dev.reckon.command.domain.account;

/**
 * The folded state of an {@link Account} at a point in time, captured so the aggregate can
 * be reconstructed without replaying from its first event.
 *
 * <p>The version is stored alongside the snapshot (as a column) rather than inside it, so
 * this record is just the balance-relevant state. Reconstructing is
 * {@link Account#fromSnapshot} plus a replay of the events after the snapshot version.
 */
public record AccountSnapshot(
        boolean opened,
        String owner,
        String currency,
        long balanceMinor
) {}
