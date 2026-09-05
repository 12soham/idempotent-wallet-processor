package com.assignment.wallet.exception;



import java.util.UUID;

/**
 * Exception thrown when no wallet exists for the specified userId.
 * Maps to HTTP 404 Not Found.
 */
public class WalletNotFoundException extends RuntimeException {

    private final UUID userId;

    public WalletNotFoundException(UUID userId) {
        super(String.format("Wallet not found for userId: '%s'.", userId));
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}

