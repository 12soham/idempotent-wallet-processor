package com.assignment.wallet.repository;


import com.assignment.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Wallet entity operations.
 * Supports row-level pessimistic write locking to prevent race conditions during concurrent updates.
 */
@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * Fetches a wallet by userId with an exclusive database-level write lock (SELECT ... FOR UPDATE).
     * Any concurrent transaction attempting to read with lock or modify this row will block
     * until the current transaction commits or rolls back.
     *
     * @param userId the UUID of the user owning the wallet
     * @return an Optional containing the locked Wallet, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
    Optional<Wallet> findByUserIdWithLock(@Param("userId") UUID userId);

    /**
     * Read-only retrieval of a wallet by userId without acquiring an exclusive lock.
     *
     * @param userId the UUID of the user owning the wallet
     * @return an Optional containing the Wallet, or empty if not found
     */
    Optional<Wallet> findByUserId(UUID userId);
}

