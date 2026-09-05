package com.assignment.wallet.service;


import com.assignment.wallet.dto.TransactionRequest;
import com.assignment.wallet.dto.TransactionResponse;
import com.assignment.wallet.dto.WalletResponse;
import com.assignment.wallet.entity.Transaction;
import com.assignment.wallet.entity.Wallet;
import com.assignment.wallet.exception.DuplicateTransactionException;
import com.assignment.wallet.exception.InsufficientFundsException;
import com.assignment.wallet.exception.WalletNotFoundException;
import com.assignment.wallet.repository.TransactionRepository;
import com.assignment.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse processTransaction(TransactionRequest request) {
        UUID transactionId = request.getTransactionId();
        UUID userId = request.getUserId();
        BigDecimal amount = request.getAmount().setScale(4, RoundingMode.HALF_UP);

        log.info("Initiating transaction processing: transactionId={}, userId={}, amount={}, type={}",
                transactionId, userId, amount, request.getType());

        // Step 1 & 2: Application-level Idempotency Check (Fast fail if already committed)
        if (transactionRepository.existsByTransactionId(transactionId)) {
            log.warn("Idempotency violation: transactionId={} has already been processed.", transactionId);
            throw new DuplicateTransactionException(transactionId);
        }

        // Step 3: Acquire database-level row lock (SELECT ... FOR UPDATE) on the target wallet
        // This serializes all debit attempts against this wallet and prevents race conditions / lost updates.
        Wallet wallet = walletRepository.findByUserIdWithLock(userId)
                .orElseThrow(() -> {
                    log.error("Wallet not found for userId={}", userId);
                    return new WalletNotFoundException(userId);
                });

        // Step 4: Re-check idempotency under lock
        // In high-concurrency bursts with identical transactionId, another thread may have acquired
        // the lock and committed right before this thread was unblocked.
        if (transactionRepository.existsByTransactionId(transactionId)) {
            log.warn("Idempotency violation under lock: transactionId={} was processed by concurrent thread.", transactionId);
            throw new DuplicateTransactionException(transactionId);
        }

        // Step 5 & 6: Balance Validation
        BigDecimal currentBalance = wallet.getBalance().setScale(4, RoundingMode.HALF_UP);
        if (currentBalance.compareTo(amount) < 0) {
            log.warn("Insufficient funds for userId={}: balance={}, debitAmount={}",
                    userId, currentBalance, amount);
            throw new InsufficientFundsException(userId, currentBalance, amount);
        }

        // Step 7 & 8: Deduct amount and persist updated wallet
        BigDecimal updatedBalance = currentBalance.subtract(amount);
        wallet.setBalance(updatedBalance);
        walletRepository.save(wallet);

        // Step 9: Save immutable transaction ledger record
        Transaction transaction = Transaction.builder()
                .transactionId(transactionId)
                .userId(userId)
                .amount(amount)
                .type(request.getType())
                .createdAt(Instant.now())
                .build();

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            // Safety net: Database unique constraint on transaction_id triggered by race condition
            log.warn("Unique constraint violation on transactionId={}: {}", transactionId, ex.getMessage());
            throw new DuplicateTransactionException(transactionId,
                    "Duplicate transaction detected: transactionId has already been committed.");
        }

        log.info("Transaction successfully processed: transactionId={}, userId={}, remainingBalance={}",
                transactionId, userId, updatedBalance);

        // Step 10: Return success response
        return TransactionResponse.builder()
                .transactionId(transactionId)
                .userId(userId)
                .amount(amount)
                .type(request.getType())
                .status("SUCCESS")
                .remainingBalance(updatedBalance.setScale(2, RoundingMode.HALF_UP))
                .processedAt(transaction.getCreatedAt())
                .message("Transaction processed successfully.")
                .build();
    }

    @Transactional
    public WalletResponse createOrFundWallet(UUID userId, BigDecimal initialBalance) {
        BigDecimal balance = (initialBalance != null ? initialBalance : BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);

        Wallet wallet = walletRepository.findByUserId(userId)
                .map(existing -> {
                    existing.setBalance(existing.getBalance().add(balance));
                    return walletRepository.save(existing);
                })
                .orElseGet(() -> walletRepository.save(
                        Wallet.builder()
                                .userId(userId)
                                .balance(balance)
                                .build()
                ));

        return WalletResponse.builder()
                .userId(wallet.getUserId())
                .balance(wallet.getBalance().setScale(2, RoundingMode.HALF_UP))
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }


    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));

        return WalletResponse.builder()
                .userId(wallet.getUserId())
                .balance(wallet.getBalance().setScale(2, RoundingMode.HALF_UP))
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }
}

