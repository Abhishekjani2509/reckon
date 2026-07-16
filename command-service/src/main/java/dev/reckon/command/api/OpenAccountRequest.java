package dev.reckon.command.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param idempotencyKey required, not optional: this is a payments API, and a client
 *     that cannot name its retries cannot be de-duplicated. Demanding it now keeps the
 *     contract honest even though nothing de-duplicates on it yet.
 */
public record OpenAccountRequest(
        @NotBlank String owner,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
        @NotBlank String idempotencyKey
) {}
