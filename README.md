# Idempotent Payment / Wallet Event Processor

A high-performance, production-grade Spring Boot 3 service engineered for strictly serialized, race-condition-free, idempotent wallet debit processing using database-level pessimistic locking (`PESSIMISTIC_WRITE`) and in-memory H2 database persistence.

---

## Table of Contents
- [Architecture & Design](#architecture--design)
- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [System Flow & Idempotency](#system-flow--idempotency)
- [Project Structure](#project-structure)
- [API Documentation](#api-documentation)
- [H2 Database Console & SQL Logging](#h2-database-console--sql-logging)
- [Setup & Running Locally](#setup--running-locally)
- [Running Automated Tests](#running-automated-tests)
- [Design Decisions](#design-decisions)

---

## Architecture & Design

In high-throughput financial and payment event processors, two critical risks threaten ledger integrity:
1. **Double-Spend & Lost Updates (Race Conditions):** When concurrent requests hit the same wallet simultaneously, reading the balance concurrently leads to inaccurate balance deductions and negative balances.
2. **Duplicate Transactions (At-Least-Once Delivery):** When upstream systems or webhook delivery mechanisms retry requests due to network timeouts, the same debit event could be executed multiple times.

This application provides mathematical consistency through:
- **Pessimistic Row-Level Locking (`PESSIMISTIC_WRITE`):** Issues `SELECT ... FOR UPDATE` on the specific wallet record, forcing all debit requests for a user into a serialized execution queue at the database level.
- **Two-Tier Idempotency Defense:**
    1. *Application-level pre-check*: Fast lookup `existsByTransactionId(UUID)` to quickly reject previously settled transactions.
    2. *Database-level unique constraint*: Uniqueness enforcement on `transactions(transaction_id)` with transactional rollback and exception translation to HTTP 409 Conflict for concurrent race arrivals.
- **Atomic Balance Deduction:** Strict arithmetic checking using `BigDecimal` preventing negative balances (`currentBalance.compareTo(amount) >= 0`).

---

## Key Features

- **Strict Idempotency:** Guarantees that even under heavy concurrent spamming of identical `transactionId`s, the balance is deducted exactly once.
- **Zero Race Conditions:** 10 concurrent requests of ₹100 against a ₹500 wallet result in exactly 5 successes, 5 insufficient fund rejections, and a balance of exactly ₹0.00 (never negative).
- **Clean Architecture & Separation of Concerns:** Layered into Controller, Service, Repository, Entity, and DTO layers.
- **RFC-7807 Compliant Error Responses:** Standardized error structures with detailed validation errors and timestamps.
- **Embedded H2 Console & Full SQL Tracing:** Pre-configured with H2 web console and detailed Hibernate SQL statement and bind-parameter logging.

---

## Tech Stack

| Technology | Purpose |
| :--- | :--- |
| **Java 17+** | Modern LTS Java runtime |
| **Spring Boot 3.2.x** | Enterprise framework for web & dependency injection |
| **Spring Data JPA / Hibernate 6** | ORM, row locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`), transaction management |
| **H2 Database** | Fast in-memory relational persistence |
| **Project Lombok** | Eliminates boilerplate code |
| **Jakarta Validation** | Declarative payload constraints (`@NotNull`, `@DecimalMin`, `@Digits`) |
| **JUnit 5 & AssertJ** | Integration test suite with multi-threaded concurrency validation |
| **Maven & Maven Wrapper** | Deterministic, reproducible builds |

---


## Project Structure

```
idempotent-wallet-processor
├── pom.xml
├── mvnw / mvnw.cmd
├── README.md
├── DECISIONS.md
└── src
    ├── main
    │   ├── java
    │   │   └── com
    │   │       └── assignment
    │   │           └── wallet
    │   │               ├── WalletApplication.java
    │   │               ├── controller
    │   │               │   └── TransactionController.java
    │   │               ├── service
    │   │               │   └── TransactionService.java
    │   │               ├── repository
    │   │               │   ├── WalletRepository.java
    │   │               │   └── TransactionRepository.java
    │   │               ├── entity
    │   │               │   ├── Wallet.java
    │   │               │   ├── Transaction.java
    │   │               │   └── TransactionType.java
    │   │               ├── dto
    │   │               │   ├── TransactionRequest.java
    │   │               │   ├── TransactionResponse.java
    │   │               │   ├── WalletCreateRequest.java
    │   │               │   ├── WalletResponse.java
    │   │               │   └── ErrorResponse.java
    │   │               └── exception
    │   │                   ├── DuplicateTransactionException.java
    │   │                   ├── InsufficientFundsException.java
    │   │                   ├── WalletNotFoundException.java
    │   │                   └── GlobalExceptionHandler.java
    │   └── resources
    │       └── application.yml
    └── test
        └── java
            └── com
                └── assignment
                    └── wallet
                        └── TransactionIntegrationTest.java
```

---

## API Documentation

### 1. Process Transaction (Debit)
**Endpoint:** `POST /api/v1/transactions/process`

#### Request Payload:
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "amount": 250.00,
  "type": "DEBIT"
}
```

#### Success Response (`HTTP 200 OK`):
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "amount": 250.0000,
  "type": "DEBIT",
  "status": "SUCCESS",
  "remainingBalance": 750.00,
  "processedAt": "2026-09-05T08:00:00Z",
  "message": "Transaction processed successfully."
}
```

#### Duplicate Transaction (`HTTP 409 Conflict`):
```json
{
  "timestamp": "2026-09-05T08:00:01Z",
  "status": 409,
  "error": "Conflict",
  "message": "Duplicate transaction detected: transactionId '3fa85f64-5717-4562-b3fc-2c963f66afa6' has already been processed.",
  "path": "/api/v1/transactions/process"
}
```

#### Insufficient Balance (`HTTP 422 Unprocessable Entity`):
```json
{
  "timestamp": "2026-09-05T08:00:02Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient funds for userId '7c9e6679-7425-40de-944b-e07fc1f90ae7': requested amount 250.0000 exceeds current balance 100.0000.",
  "path": "/api/v1/transactions/process"
}
```

#### Validation Error (`HTTP 400 Bad Request`):
```json
{
  "timestamp": "2026-09-05T08:00:03Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/v1/transactions/process",
  "validationErrors": {
    "amount": "amount must be strictly positive (greater than 0.00)"
  }
}
```

---

### 2. Helper Endpoints for Testing & Verification

#### Create / Fund Wallet
**Endpoint:** `POST /api/v1/wallets`
```json
{
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "initialBalance": 1000.00
}
```
**Response:** `HTTP 201 Created`

#### Get Wallet Status
**Endpoint:** `GET /api/v1/wallets/{userId}`
**Response:** `HTTP 200 OK`
```json
{
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "balance": 1000.00,
  "createdAt": "2026-09-05T07:55:00Z",
  "updatedAt": "2026-09-05T07:55:00Z"
}
```

---

## H2 Database Console & SQL Logging

- **Console URL:** `http://localhost:8080/h2-console`
- **JDBC URL:** `jdbc:h2:mem:walletdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`
- **User:** `sa`
- **Password:** *(leave blank)*

Hibernate SQL queries and parameter bindings are logged to standard output at `DEBUG` and `TRACE` levels respectively.

---

## Setup & Running Locally

### Prerequisites
- JDK 17 or higher (`java -version`)
- Maven 3.8+ (or use the included `./mvnw` script)

### Running the Application
```bash
# Using Maven wrapper
./mvnw spring-boot:run

# Or using local Maven
mvn spring-boot:run
```

---

## Running Automated Tests

Run the full integration test suite covering happy paths, multi-threaded idempotency bursts, and race condition debit limits:

```bash
# Using Maven wrapper
./mvnw test

# Or using local Maven
mvn test
```

### Verified Test Cases:
1. `Processes a single valid debit transaction successfully`: Balance 1000 -> Debit 100 -> Balance 900.
2. `Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.`: 3 concurrent threads fire same transactionId -> exactly 1 succeeds, 2 return HTTP 409, DB records 1 transaction, balance = 900.
3. `Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.`: 10 concurrent unique transactions hit ₹500 balance -> exactly 5 succeed, 5 fail with HTTP 422, balance = ₹0.00, balance never becomes negative.
