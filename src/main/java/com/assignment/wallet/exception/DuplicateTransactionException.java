package com.assignment.wallet.exception;



import java.util.UUID;

/**
 * Exception thrown when a transaction with the same transactionId has already been processed or is being processed.
 * Maps to HTTP 409 Conflict.
 */
public class DuplicateTransactionException extends RuntimeException {

    private final UUID transactionId;

    public DuplicateTransactionException(UUID transactionId) {
        super(String.format("Duplicate transaction detected: transactionId '%s' has already been processed.", transactionId));
        this.transactionId = transactionId;
    }

    public DuplicateTransactionException(UUID transactionId, String message) {
        super(message);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}

