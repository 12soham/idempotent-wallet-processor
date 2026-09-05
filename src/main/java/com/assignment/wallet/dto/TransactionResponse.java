package com.assignment.wallet.dto;



import com.assignment.wallet.entity.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound response payload following successful transaction processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID transactionId;
    private UUID userId;
    private BigDecimal amount;
    private TransactionType type;
    private String status;
    private BigDecimal remainingBalance;
    private Instant processedAt;
    private String message;
}

