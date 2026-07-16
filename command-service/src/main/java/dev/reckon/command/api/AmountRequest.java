package dev.reckon.command.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/**
 * A deposit or withdrawal.
 *
 * @param amountMinor integer minor units (cents). Never a decimal: the wire format is
 *     part of the money-handling discipline, and accepting "10.50" would invite a float
 *     somewhere up the call chain.
 */
public record AmountRequest(
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
        @NotBlank String idempotencyKey
) {}
