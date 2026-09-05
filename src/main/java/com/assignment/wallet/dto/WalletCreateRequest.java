package com.assignment.wallet.dto;



import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for creating or funding a user's wallet.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletCreateRequest {

    @NotNull(message = "userId cannot be null")
    private UUID userId;

    @NotNull(message = "initialBalance cannot be null")
    @DecimalMin(value = "0.00", message = "initialBalance cannot be negative")
    private BigDecimal initialBalance;
}

