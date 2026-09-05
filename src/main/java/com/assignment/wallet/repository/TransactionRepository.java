package com.assignment.wallet.repository;


import com.assignment.wallet.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Transaction ledger records.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Verifies if a transaction with the given transactionId has already been recorded.
     * Used for the initial application-level idempotency pre-check.
     *
     * @param transactionId unique transaction identifier
     * @return true if a transaction with this ID already exists, false otherwise
     */
    boolean existsByTransactionId(UUID transactionId);

    /**
     * Finds a transaction record by its unique transactionId.
     *
     * @param transactionId unique transaction identifier
     * @return Optional containing the Transaction if found
     */
    Optional<Transaction> findByTransactionId(UUID transactionId);
}

