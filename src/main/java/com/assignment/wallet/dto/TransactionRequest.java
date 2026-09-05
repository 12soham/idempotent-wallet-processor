package com.assignment.wallet.dto;



import com.assignment.wallet.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound request payload for processing a wallet transaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {

    @NotNull(message = "transactionId cannot be null")
    private UUID transactionId;

    @NotNull(message = "userId cannot be null")
    private UUID userId;

    @NotNull(message = "amount cannot be null")
    @DecimalMin(value = "0.01", message = "amount must be strictly positive (greater than 0.00)")
    @Digits(integer = 15, fraction = 4, message = "amount exceeds allowed integer/fraction digits")
    private BigDecimal amount;

    @NotNull(message = "type cannot be null (must be DEBIT)")
    private TransactionType type;
}

