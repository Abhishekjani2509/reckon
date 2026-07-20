package dev.reckon.command.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * A request to transfer funds. The source is the path account; this names the other side.
 *
 * @param amountMinor integer minor units, positive
 * @param idempotencyKey required — the correlation for the whole transfer and every event
 *     the saga emits from it
 */
public record TransferRequest(
        @NotBlank String destinationAccountId,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
        @NotBlank String idempotencyKey
) {}
