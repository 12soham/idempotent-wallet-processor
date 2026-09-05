package com.assignment.wallet;


import com.assignment.wallet.dto.ErrorResponse;
import com.assignment.wallet.dto.TransactionRequest;
import com.assignment.wallet.dto.TransactionResponse;
import com.assignment.wallet.dto.WalletCreateRequest;
import com.assignment.wallet.dto.WalletResponse;
import com.assignment.wallet.entity.TransactionType;
import com.assignment.wallet.entity.Wallet;
import com.assignment.wallet.repository.TransactionRepository;
import com.assignment.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test suite verifying transaction idempotency,
 * pessimistic row-level locking, and race condition handling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TransactionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    /**
     * Helper to create a wallet with initial balance via the repository.
     */
    private Wallet createWallet(UUID userId, BigDecimal initialBalance) {
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(initialBalance.setScale(4, RoundingMode.HALF_UP))
                .build();
        return walletRepository.save(wallet);
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully")
    void processesSingleValidDebitTransactionSuccessfully() {
        // Steps:
        // 1. Create wallet balance = 1000
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        createWallet(userId, new BigDecimal("1000.00"));

        // 2. Debit = 100
        TransactionRequest request = TransactionRequest.builder()
                .transactionId(transactionId)
                .userId(userId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEBIT)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TransactionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/transactions/process", entity, TransactionResponse.class);

        // 3. Assert HTTP response and final balance = 900
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getBody().getTransactionId()).isEqualTo(transactionId);
        assertThat(response.getBody().getRemainingBalance()).isEqualByComparingTo(new BigDecimal("900.00"));

        // Assert database state
        Wallet updatedWallet = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(transactionRepository.existsByTransactionId(transactionId)).isTrue();
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void sends3IdenticalTransactionIDsSimultaneouslyEnsuresBalanceDeductedOnce() throws InterruptedException {
        // Steps:
        // 1. Create wallet balance = 1000
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        createWallet(userId, new BigDecimal("1000.00"));

        // 2. Send 3 concurrent requests with same transactionId
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(threadCount);

        List<ResponseEntity<String>> responses = Collections.synchronizedList(new ArrayList<>());

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(transactionId)
                .userId(userId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEBIT)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TransactionRequest> entity = new HttpEntity<>(request, headers);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startSignal.await(); // Synchronize all threads to fire at the exact same instant
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            getBaseUrl() + "/transactions/process", entity, String.class);
                    responses.add(response);
                } catch (Exception e) {
                    // unexpected thread exception
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startSignal.countDown();
        boolean completed = doneSignal.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(threadCount);

        // 3. Assert:
        // - only one transaction saved
        // - balance = 900
        // - duplicates rejected with 409 Conflict
        long successCount = responses.stream()
                .filter(res -> res.getStatusCode() == HttpStatus.OK)
                .count();

        long duplicateCount = responses.stream()
                .filter(res -> res.getStatusCode() == HttpStatus.CONFLICT)
                .count();

        assertThat(successCount).as("Exactly one request should succeed").isEqualTo(1);
        assertThat(duplicateCount).as("Remaining requests should be rejected as duplicates (409 Conflict)").isEqualTo(2);

        // Database assertions
        Wallet finalWallet = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(finalWallet.getBalance()).as("Balance should only be deducted once to 900.00")
                .isEqualByComparingTo(new BigDecimal("900.00"));

        assertThat(transactionRepository.count()).as("Only 1 transaction record must exist in DB").isEqualTo(1);
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void sends10ConcurrentDebitRequestsForWalletWith500BalanceEnsuresFinalBalance0And5Fail() throws InterruptedException {
        // Steps:
        // 1. Create wallet balance = 500
        UUID userId = UUID.randomUUID();
        createWallet(userId, new BigDecimal("500.00"));

        // 2. Generate 10 unique transactionIds & send 10 concurrent debit requests of ₹100
        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(requestCount);

        List<ResponseEntity<String>> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger insufficientFundsCounter = new AtomicInteger(0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int i = 0; i < requestCount; i++) {
            UUID uniqueTxId = UUID.randomUUID();
            TransactionRequest request = TransactionRequest.builder()
                    .transactionId(uniqueTxId)
                    .userId(userId)
                    .amount(new BigDecimal("100.00"))
                    .type(TransactionType.DEBIT)
                    .build();
            HttpEntity<TransactionRequest> entity = new HttpEntity<>(request, headers);

            executor.submit(() -> {
                try {
                    startSignal.await(); // Synchronize all 10 threads to hit simultaneously
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            getBaseUrl() + "/transactions/process", entity, String.class);
                    responses.add(response);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCounter.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                        insufficientFundsCounter.incrementAndGet();
                    }
                } catch (Exception e) {
                    // log or track
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startSignal.countDown();
        boolean completed = doneSignal.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(requestCount);

        // 3. Assert:
        // - 5 successful transactions (200 OK)
        // - 5 failed transactions (422 Unprocessable Entity due to insufficient funds)
        // - final balance = 0
        // - no negative balance
        assertThat(successCounter.get()).as("Exactly 5 transactions must succeed").isEqualTo(5);
        assertThat(insufficientFundsCounter.get()).as("Exactly 5 transactions must fail with Insufficient Funds").isEqualTo(5);

        // Assert database ledger
        assertThat(transactionRepository.count()).as("Exactly 5 transactions must be saved in the database").isEqualTo(5);

        Wallet finalWallet = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(finalWallet.getBalance()).as("Final balance must be exactly 0.00").isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(finalWallet.getBalance().compareTo(BigDecimal.ZERO)).as("Balance must never become negative").isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Fails debit request when balance is insufficient (single-thread verification)")
    void failsDebitWhenInsufficientFunds() {
        UUID userId = UUID.randomUUID();
        createWallet(userId, new BigDecimal("50.00"));

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEBIT)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TransactionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/transactions/process", entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(422);
        assertThat(response.getBody().getMessage()).contains("Insufficient funds");

        // Verify balance remained unchanged
        Wallet wallet = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("Fails transaction request when wallet does not exist")
    void failsWhenWalletNotFound() {
        UUID nonExistentUserId = UUID.randomUUID();

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(nonExistentUserId)
                .amount(new BigDecimal("50.00"))
                .type(TransactionType.DEBIT)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TransactionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/transactions/process", entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("Wallet not found");
    }

    @Test
    @DisplayName("Validates input payload and rejects negative or zero debit amounts")
    void validatesInputPayload() {
        UUID userId = UUID.randomUUID();
        createWallet(userId, new BigDecimal("100.00"));

        TransactionRequest invalidRequest = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("-10.00"))
                .type(TransactionType.DEBIT)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TransactionRequest> entity = new HttpEntity<>(invalidRequest, headers);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/transactions/process", entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getValidationErrors()).containsKey("amount");
    }

    @Test
    @DisplayName("Initializes and queries wallet via wallet endpoints")
    void initializesAndQueriesWalletViaEndpoints() {
        UUID userId = UUID.randomUUID();
        WalletCreateRequest createRequest = WalletCreateRequest.builder()
                .userId(userId)
                .initialBalance(new BigDecimal("500.00"))
                .build();

        ResponseEntity<WalletResponse> createResponse = restTemplate.postForEntity(
                getBaseUrl() + "/wallets", createRequest, WalletResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().getUserId()).isEqualTo(userId);
        assertThat(createResponse.getBody().getBalance()).isEqualByComparingTo(new BigDecimal("500.00"));

        ResponseEntity<WalletResponse> getResponse = restTemplate.getForEntity(
                getBaseUrl() + "/wallets/" + userId, WalletResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getBalance()).isEqualByComparingTo(new BigDecimal("500.00"));
    }
}
