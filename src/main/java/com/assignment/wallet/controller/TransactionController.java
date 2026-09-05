package com.assignment.wallet.controller;


import com.assignment.wallet.dto.TransactionRequest;
import com.assignment.wallet.dto.TransactionResponse;
import com.assignment.wallet.dto.WalletCreateRequest;
import com.assignment.wallet.dto.WalletResponse;
import com.assignment.wallet.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller exposing endpoints for transaction processing and wallet management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;


    @PostMapping("/transactions/process")
    public ResponseEntity<TransactionResponse> processTransaction(
            @Valid @RequestBody TransactionRequest request) {
        log.info("Received transaction request for transactionId={}", request.getTransactionId());
        TransactionResponse response = transactionService.processTransaction(request);
        return ResponseEntity.ok(response);
    }


    @PostMapping("/wallets")
    public ResponseEntity<WalletResponse> createOrFundWallet(
            @Valid @RequestBody WalletCreateRequest request) {
        log.info("Creating or funding wallet for userId={}, initialBalance={}",
                request.getUserId(), request.getInitialBalance());
        WalletResponse response = transactionService.createOrFundWallet(
                request.getUserId(), request.getInitialBalance());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @GetMapping("/wallets/{userId}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID userId) {
        log.info("Querying wallet details for userId={}", userId);
        WalletResponse response = transactionService.getWallet(userId);
        return ResponseEntity.ok(response);
    }
}

