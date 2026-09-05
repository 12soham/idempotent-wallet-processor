# Architectural & Engineering Decisions

## 1. Concurrency Handling

### The Problem
Financial systems require absolute transactional correctness. When multiple debit requests arrive concurrently for the same wallet, standard `READ_COMMITTED` transactions reading balance `B` and calculating `B - debit` without locks experience the **Lost Update anomaly**:
- Thread 1 reads balance ₹500.
- Thread 2 reads balance ₹500.
- Thread 1 writes ₹400 (500 - 100).
- Thread 2 writes ₹400 (500 - 100).
- **Result:** Two debits occurred (₹200 total), but balance only decreased by ₹100, violating consistency and enabling double-spending or negative balances.

### The Solution
We implemented **Database-Level Row Locking using JPA `PESSIMISTIC_WRITE`**:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
Optional<Wallet> findByUserIdWithLock(@Param("userId") UUID userId);
```
- In SQL, this compiles to: `SELECT ... FROM wallets WHERE user_id = ? FOR UPDATE`.
- The database acquires an exclusive row lock on that user's wallet record for the lifespan of the transaction.
- Concurrent transactions attempting to execute a debit against the same user's wallet are placed in a database-managed lock queue.
- Transactions are serialized per wallet. Crucially, debits for *different* users do not block one another, ensuring maximum horizontal parallelism across accounts while guaranteeing single-threaded consistency per account.

---

## 2. Why `PESSIMISTIC_WRITE` Was Chosen

We evaluated two standard locking paradigms:

### Option A: Optimistic Locking (`@Version`)
- **Mechanism:** Transactions read data without locks. When updating, the query checks `WHERE id = ? AND version = ?`. If version changed, `OptimisticLockException` is thrown.
- **Why it was rejected:**
    - In high-contention debit bursts (e.g., 10 concurrent payments), 9 transactions would abort with optimistic locking failures.
    - To prevent legitimate customer payments from failing, the application would need complex retry loops with exponential backoff and jitter.
    - Retrying debits in application memory increases latency, burns CPU cycles, and risks retry storm starvation under sustained load.
    - More importantly, balance availability checks are state-dependent; retrying an optimistic lock failure multiple times when balance is actually decreasing wastes database I/O.

### Option B: Pessimistic Locking (`PESSIMISTIC_WRITE`) — *Chosen*
- **Mechanism:** The database serializes operations immediately at the row level.
- **Benefits:**
    - **No wasted work:** Each request waits its turn, reads the freshest, committed balance, and evaluates against available funds.
    - **Zero application retry complexity:** Eliminates race condition failures and ensures FIFO-like fair execution.
    - **Deterministic balance guarantees:** Exactly 5 out of 10 requests succeed on a ₹500 balance with ₹100 debits without false-positive lock aborts.
    - **Native database capability:** Leveraged directly by PostgreSQL, MySQL, and H2 without distributed locking overhead.

---

## 3. How Idempotency Was Implemented

Idempotency guarantees that submitting the same `transactionId` multiple times yields the same result and deducts the balance exactly once.

We designed a **Two-Tier Defensive Architecture**:

### Tier 1: Application-Level Pre-Check
```java
if (transactionRepository.existsByTransactionId(transactionId)) {
    throw new DuplicateTransactionException(transactionId);
}
```
- **Rationale:** 99% of duplicate requests arrive sequentially (retries after client timeout or user double-clicking).
- **Advantage:** Short-circuits immediately with HTTP 409 Conflict without acquiring row locks or placing load on the wallet table.

### Tier 2: Double-Check Under Lock & Database Unique Constraint
When two or more identical `transactionId` requests arrive at the exact same millisecond:
1. Both might pass Tier 1 before either commits.
2. Both attempt to acquire the `PESSIMISTIC_WRITE` lock on the wallet.
3. Thread A acquires the lock and enters the critical section.
4. Thread B blocks at the database lock.
5. Thread A commits the transaction and saves the transaction record with `transactionId`.
6. Thread B is granted the lock.
7. **Double-Check:** Thread B immediately executes `existsByTransactionId` *while holding the lock*, detects Thread A's commit, and aborts before touching the balance!
8. **Hard Safety Net:** Even if a race condition spanned different entities or distributed nodes, `transactions(transaction_id)` has a database `UNIQUE` constraint. If a duplicate insert is attempted, Hibernate throws `DataIntegrityViolationException`, which is caught and mapped to `DuplicateTransactionException` (HTTP 409 Conflict).

This multi-layer pattern guarantees balance deduction occurs strictly once.

---

## 4. Potential Improvements for Large-Scale Production

While the current architecture is production-grade for single-database workloads, scaling to tens of thousands of requests per second across distributed geographic regions could benefit from:

1. **Distributed Redis Locks (Redlock):**
    - For microservices sharded across multiple database clusters, a distributed lock manager (e.g., Redisson with Redis) can serialize requests before hitting the database, offloading connection pool pressure.
2. **Transactional Outbox & Event-Driven Architecture:**
    - After updating the wallet and transaction table in a single local transaction, an Outbox Event table can publish a `WalletDebitedEvent` to Apache Kafka or RabbitMQ for downstream accounting, fraud analysis, and audit systems.
3. **Idempotency Response Caching:**
    - Instead of just returning 409 Conflict on duplicates, return the original cached `TransactionResponse` body (matching Stripe/Adyen behavior where idempotent calls replay the original 200 OK response).
4. **Lock Timeout Configuration:**
    - Configure explicit query lock timeouts (`jakarta.persistence.lock.timeout` = 3000ms) to fail fast if a database row lock is held abnormally long by a stalled transaction.

---

## 5. Challenges Faced During Implementation & Mitigation

1. **Sub-Millisecond Concurrent Duplicate Arrivals:**
    - *Challenge:* Relying solely on `existsByTransactionId` allows race conditions if two threads execute the check concurrently before either commits.
    - *Mitigation:* Combined row-level pessimistic locking, in-lock double-check, and database unique constraints with exception translation.
2. **H2 In-Memory Lock Semantics:**
    - *Challenge:* In-memory H2 behaves differently from MySQL/PostgreSQL if connection pools or isolation levels aren't properly managed.
    - *Mitigation:* Configured `DB_CLOSE_DELAY=-1`, used Spring's `@Transactional(isolation = Isolation.READ_COMMITTED)`, and executed integration tests with `RANDOM_PORT` web environment to test multi-threaded HTTP dispatch against a live server.
3. **Monetary Precision & Float Inaccuracies:**
    - *Challenge:* Floating point numbers (`double`, `float`) introduce precision drift in currency arithmetic.
    - *Mitigation:* Strictly enforced `BigDecimal` with 4-decimal precision internally and `HALF_UP` rounding mode, formatting output balances to 2 decimal places.
