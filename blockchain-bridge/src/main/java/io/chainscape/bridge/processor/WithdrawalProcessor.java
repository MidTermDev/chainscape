package io.chainscape.bridge.processor;

import io.chainscape.bridge.solana.TokenOperations;
import io.chainscape.bridge.security.RateLimiter;
import io.chainscape.bridge.security.FraudDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Processes withdrawal requests by minting tokens to player wallets.
 * Implements rate limiting, fraud detection, and atomic operations.
 */
public class WithdrawalProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WithdrawalProcessor.class);

    private final TokenOperations tokenOperations;
    private final RateLimiter rateLimiter;
    private final FraudDetector fraudDetector;
    private final Connection dbConnection;
    private final BlockingQueue<WithdrawalRequest> requestQueue;
    private final ExecutorService executor;

    private volatile boolean running = false;

    // Configuration
    private static final int QUEUE_CAPACITY = 1000;
    private static final int PROCESSOR_THREADS = 2;
    private static final long MAX_WITHDRAWAL_AMOUNT = 2_147_483_647L; // Max int

    public WithdrawalProcessor(
            TokenOperations tokenOperations,
            RateLimiter rateLimiter,
            FraudDetector fraudDetector,
            Connection dbConnection) {
        this.tokenOperations = tokenOperations;
        this.rateLimiter = rateLimiter;
        this.fraudDetector = fraudDetector;
        this.dbConnection = dbConnection;
        this.requestQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.executor = Executors.newFixedThreadPool(PROCESSOR_THREADS);
    }

    /**
     * Starts the withdrawal processor.
     */
    public void start() {
        running = true;
        for (int i = 0; i < PROCESSOR_THREADS; i++) {
            executor.submit(this::processLoop);
        }
        logger.info("Withdrawal processor started with {} threads", PROCESSOR_THREADS);
    }

    /**
     * Stops the withdrawal processor.
     */
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Withdrawal processor stopped");
    }

    /**
     * Submits a withdrawal request for processing.
     *
     * @return The transaction ID if queued successfully, null otherwise
     */
    public WithdrawalResult submitWithdrawal(
            UUID playerUUID,
            String solanaAddress,
            long amount,
            GPDeductionCallback deductGP) {

        // Validate amount
        if (amount <= 0 || amount > MAX_WITHDRAWAL_AMOUNT) {
            return new WithdrawalResult(WithdrawalStatus.INVALID_AMOUNT, null,
                    "Invalid amount: must be between 1 and " + MAX_WITHDRAWAL_AMOUNT);
        }

        // Check circuit breaker
        if (isCircuitBreakerOpen()) {
            return new WithdrawalResult(WithdrawalStatus.CIRCUIT_BREAKER_OPEN, null,
                    "Withdrawals temporarily disabled");
        }

        // Check rate limits
        if (!rateLimiter.checkWithdrawalLimit(playerUUID, amount)) {
            return new WithdrawalResult(WithdrawalStatus.RATE_LIMITED, null,
                    "Rate limit exceeded");
        }

        // Run fraud check
        FraudDetector.RiskScore riskScore = fraudDetector.evaluateWithdrawal(
                playerUUID, amount, solanaAddress);

        if (riskScore.getLevel() == FraudDetector.RiskLevel.CRITICAL) {
            logger.warn("Withdrawal blocked due to high risk: player={}, amount={}",
                    playerUUID, amount);
            return new WithdrawalResult(WithdrawalStatus.BLOCKED_FRAUD, null,
                    "Withdrawal flagged for review");
        }

        // Generate transaction ID
        String transactionId = UUID.randomUUID().toString();

        try {
            // Atomically deduct GP from player
            if (!deductGP.deductGP(amount)) {
                return new WithdrawalResult(WithdrawalStatus.INSUFFICIENT_BALANCE, null,
                        "Insufficient GP balance");
            }

            // Create pending transaction record
            long dbTxId = createPendingWithdrawal(playerUUID, solanaAddress, amount, transactionId);

            // Record rate limit usage
            rateLimiter.recordWithdrawal(playerUUID, amount);

            // Queue the request
            WithdrawalRequest request = new WithdrawalRequest(
                    dbTxId, transactionId, playerUUID, solanaAddress, amount, System.currentTimeMillis());

            if (!requestQueue.offer(request, 5, TimeUnit.SECONDS)) {
                // Queue full - refund GP and rollback
                logger.error("Withdrawal queue full, refunding GP");
                markWithdrawalFailed(dbTxId, "Queue full");
                deductGP.refundGP(amount);
                return new WithdrawalResult(WithdrawalStatus.QUEUE_FULL, null,
                        "System busy, please try again later");
            }

            logger.info("Withdrawal queued: {} GP for player {} to {}",
                    amount, playerUUID, solanaAddress.substring(0, 8) + "...");

            return new WithdrawalResult(WithdrawalStatus.QUEUED, transactionId, null);

        } catch (Exception e) {
            logger.error("Error submitting withdrawal", e);
            deductGP.refundGP(amount);
            return new WithdrawalResult(WithdrawalStatus.ERROR, null, e.getMessage());
        }
    }

    /**
     * Main processing loop for withdrawal requests.
     */
    private void processLoop() {
        while (running) {
            try {
                WithdrawalRequest request = requestQueue.poll(1, TimeUnit.SECONDS);
                if (request != null) {
                    processWithdrawal(request);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in withdrawal processing loop", e);
            }
        }
    }

    /**
     * Processes a single withdrawal request.
     */
    private void processWithdrawal(WithdrawalRequest request) {
        try {
            // Update status to processing
            updateWithdrawalStatus(request.dbId, "PROCESSING", null);

            // Execute the mint transaction
            String signature = tokenOperations.mintTokens(
                    request.solanaAddress,
                    BigInteger.valueOf(request.amount)
            );

            if (signature != null) {
                // Success - update database
                completeWithdrawal(request.dbId, signature);
                logger.info("Withdrawal completed: {} GP to {} (sig: {})",
                        request.amount, request.solanaAddress.substring(0, 8) + "...",
                        signature.substring(0, 16) + "...");
            } else {
                // Mint failed - this should trigger investigation
                // GP already deducted, needs manual intervention
                markWithdrawalFailed(request.dbId, "Mint transaction failed");
                createFailedMintAlert(request);
                logger.error("Withdrawal mint failed for request {}", request.transactionId);
            }

        } catch (Exception e) {
            logger.error("Error processing withdrawal {}", request.transactionId, e);
            try {
                markWithdrawalFailed(request.dbId, e.getMessage());
            } catch (SQLException sqlEx) {
                logger.error("Failed to mark withdrawal as failed", sqlEx);
            }
        }
    }

    /**
     * Creates a pending withdrawal record in the database.
     */
    private long createPendingWithdrawal(UUID playerUUID, String solanaAddress, long amount, String txId)
            throws SQLException {
        String sql = """
            INSERT INTO blockchain_transactions
            (player_uuid, transaction_type, amount_gp, status, error_message)
            VALUES (?, 'WITHDRAWAL', ?, 'PENDING', NULL)
            RETURNING id
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            stmt.setLong(2, amount);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("id");
            }
            throw new SQLException("Failed to create pending withdrawal record");
        }
    }

    /**
     * Updates the status of a withdrawal.
     */
    private void updateWithdrawalStatus(long dbId, String status, String signature) throws SQLException {
        String sql = "UPDATE blockchain_transactions SET status = ?, solana_signature = ? WHERE id = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, signature);
            stmt.setLong(3, dbId);
            stmt.executeUpdate();
        }
    }

    /**
     * Marks a withdrawal as completed.
     */
    private void completeWithdrawal(long dbId, String signature) throws SQLException {
        String sql = """
            UPDATE blockchain_transactions
            SET status = 'CONFIRMED', solana_signature = ?, confirmed_at = NOW()
            WHERE id = ?
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, signature);
            stmt.setLong(2, dbId);
            stmt.executeUpdate();
        }
    }

    /**
     * Marks a withdrawal as failed.
     */
    private void markWithdrawalFailed(long dbId, String reason) throws SQLException {
        String sql = "UPDATE blockchain_transactions SET status = 'FAILED', error_message = ? WHERE id = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, reason);
            stmt.setLong(2, dbId);
            stmt.executeUpdate();
        }
    }

    /**
     * Creates an alert for failed mint transactions.
     */
    private void createFailedMintAlert(WithdrawalRequest request) {
        try {
            String sql = """
                INSERT INTO fraud_alerts
                (player_uuid, alert_type, severity, description, related_data)
                VALUES (?, 'FAILED_MINT', 'CRITICAL', ?, ?::jsonb)
                """;
            try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
                stmt.setObject(1, request.playerUUID);
                stmt.setString(2, "Mint transaction failed - GP deducted but tokens not sent");
                stmt.setString(3, String.format(
                        "{\"transactionId\":\"%s\",\"amount\":%d,\"address\":\"%s\"}",
                        request.transactionId, request.amount, request.solanaAddress));
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to create failed mint alert", e);
        }
    }

    /**
     * Checks if the circuit breaker is open (withdrawals disabled).
     */
    private boolean isCircuitBreakerOpen() {
        try {
            String sql = "SELECT is_open FROM circuit_breaker_state WHERE breaker_name = 'withdrawals'";
            try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getBoolean("is_open");
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to check circuit breaker state", e);
            return true; // Fail safe - disable withdrawals on error
        }
        return false;
    }

    /**
     * Gets the current queue size.
     */
    public int getQueueSize() {
        return requestQueue.size();
    }

    /**
     * Internal request object.
     */
    private static class WithdrawalRequest {
        final long dbId;
        final String transactionId;
        final UUID playerUUID;
        final String solanaAddress;
        final long amount;
        final long timestamp;

        WithdrawalRequest(long dbId, String transactionId, UUID playerUUID,
                         String solanaAddress, long amount, long timestamp) {
            this.dbId = dbId;
            this.transactionId = transactionId;
            this.playerUUID = playerUUID;
            this.solanaAddress = solanaAddress;
            this.amount = amount;
            this.timestamp = timestamp;
        }
    }

    /**
     * Result of a withdrawal submission.
     */
    public static class WithdrawalResult {
        private final WithdrawalStatus status;
        private final String transactionId;
        private final String message;

        public WithdrawalResult(WithdrawalStatus status, String transactionId, String message) {
            this.status = status;
            this.transactionId = transactionId;
            this.message = message;
        }

        public WithdrawalStatus getStatus() { return status; }
        public String getTransactionId() { return transactionId; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return status == WithdrawalStatus.QUEUED; }
    }

    /**
     * Withdrawal status codes.
     */
    public enum WithdrawalStatus {
        QUEUED,
        INVALID_AMOUNT,
        INSUFFICIENT_BALANCE,
        RATE_LIMITED,
        BLOCKED_FRAUD,
        CIRCUIT_BREAKER_OPEN,
        QUEUE_FULL,
        ERROR
    }

    /**
     * Callback interface for GP deduction.
     */
    public interface GPDeductionCallback {
        boolean deductGP(long amount);
        void refundGP(long amount);
    }
}
