package com.assignment.wallet.exception;



import java.math.BigDecimal;
import java.util.UUID;

/**
 * Exception thrown when a debit transaction exceeds the user's available wallet balance.
 * Maps to HTTP 422 Unprocessable Entity (or 400 Bad Request).
 */
public class InsufficientFundsException extends RuntimeException {

    private final UUID userId;
    private final BigDecimal currentBalance;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(UUID userId, BigDecimal currentBalance, BigDecimal requestedAmount) {
        super(String.format("Insufficient funds for userId '%s': requested amount %s exceeds current balance %s.",
                userId, requestedAmount, currentBalance));
        this.userId = userId;
        this.currentBalance = currentBalance;
        this.requestedAmount = requestedAmount;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }
}

