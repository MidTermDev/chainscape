package io.chainscape.bridge.processor;

import io.chainscape.bridge.solana.TokenOperations;
import io.chainscape.bridge.solana.TransactionMonitor;
import io.chainscape.bridge.security.FraudDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.*;
import java.sql.*;

/**
 * Processes deposits by monitoring Solana for burn transactions
 * and crediting GP to player accounts.
 */
public class DepositProcessor {
    private static final Logger logger = LoggerFactory.getLogger(DepositProcessor.class);

    private final TokenOperations tokenOperations;
    private final TransactionMonitor transactionMonitor;
    private final FraudDetector fraudDetector;
    private final Connection dbConnection;
    private final GameServerBridge gameServerBridge;
    private final ExecutorService executor;

    private volatile boolean running = false;

    // Configuration
    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final long POLL_INTERVAL_MS = 1000;

    public DepositProcessor(
            TokenOperations tokenOperations,
            TransactionMonitor transactionMonitor,
            FraudDetector fraudDetector,
            Connection dbConnection,
            GameServerBridge gameServerBridge) {
        this.tokenOperations = tokenOperations;
        this.transactionMonitor = transactionMonitor;
        this.fraudDetector = fraudDetector;
        this.dbConnection = dbConnection;
        this.gameServerBridge = gameServerBridge;
        this.executor = Executors.newFixedThreadPool(4);
    }

    /**
     * Starts the deposit processor.
     */
    public void start() {
        running = true;
        transactionMonitor.subscribeToBurnEvents(this::handleBurnEvent);
        logger.info("Deposit processor started");
    }

    /**
     * Stops the deposit processor.
     */
    public void stop() {
        running = false;
        transactionMonitor.unsubscribe();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Deposit processor stopped");
    }

    /**
     * Handles a detected burn event from the blockchain.
     */
    private void handleBurnEvent(BurnEvent event) {
        executor.submit(() -> processBurnEvent(event));
    }

    /**
     * Processes a burn event and credits GP to the player.
     */
    private void processBurnEvent(BurnEvent event) {
        String signature = event.getSignature();

        try {
            // Check if already processed (idempotency)
            if (isTransactionProcessed(signature)) {
                logger.debug("Transaction already processed: {}", signature);
                return;
            }

            // Wait for sufficient confirmations
            if (!waitForConfirmations(signature, REQUIRED_CONFIRMATIONS)) {
                logger.warn("Transaction not confirmed after timeout: {}", signature);
                return;
            }

            // Verify the burn transaction details
            BurnDetails burnDetails = tokenOperations.verifyBurnTransaction(signature);
            if (burnDetails == null) {
                logger.error("Failed to verify burn transaction: {}", signature);
                return;
            }

            // Find the player associated with this wallet
            UUID playerUUID = getPlayerByWallet(burnDetails.getFromAddress());
            if (playerUUID == null) {
                logger.warn("No player linked to wallet: {}", burnDetails.getFromAddress());
                // Store unlinked deposit for later claiming
                storeUnlinkedDeposit(signature, burnDetails);
                return;
            }

            // Run fraud detection
            FraudDetector.RiskScore riskScore = fraudDetector.evaluateDeposit(
                    playerUUID,
                    burnDetails.getAmount(),
                    burnDetails.getFromAddress()
            );

            if (riskScore.getLevel() == FraudDetector.RiskLevel.CRITICAL) {
                logger.warn("Deposit flagged as high risk: {} for player {}", signature, playerUUID);
                createFraudAlert(playerUUID, signature, riskScore);
                // Still process but flag for review
            }

            // Credit GP to player
            long gpAmount = burnDetails.getAmount().longValue();
            boolean credited = creditGPToPlayer(playerUUID, gpAmount, signature);

            if (credited) {
                // Record the transaction
                recordDeposit(playerUUID, gpAmount, signature);

                // Notify the game server
                gameServerBridge.notifyDeposit(playerUUID, gpAmount, signature);

                logger.info("Deposit credited: {} GP to player {} (tx: {})",
                        gpAmount, playerUUID, signature.substring(0, 16) + "...");
            }

        } catch (Exception e) {
            logger.error("Error processing burn event: {}", signature, e);
        }
    }

    /**
     * Waits for a transaction to reach the required confirmation count.
     */
    private boolean waitForConfirmations(String signature, int required) {
        int maxAttempts = 60; // 60 seconds max
        int attempts = 0;

        while (running && attempts < maxAttempts) {
            try {
                int confirmations = transactionMonitor.getConfirmationCount(signature);
                if (confirmations >= required) {
                    return true;
                }

                Thread.sleep(POLL_INTERVAL_MS);
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Checks if a transaction has already been processed.
     */
    private boolean isTransactionProcessed(String signature) throws SQLException {
        String sql = "SELECT COUNT(*) FROM blockchain_transactions WHERE solana_signature = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, signature);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    /**
     * Gets the player UUID associated with a wallet address.
     */
    private UUID getPlayerByWallet(String walletAddress) throws SQLException {
        String sql = "SELECT player_uuid FROM player_wallets WHERE solana_address = ? AND is_active = true";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, walletAddress);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("player_uuid"));
            }
        }
        return null;
    }

    /**
     * Credits GP to a player's in-game account.
     */
    private boolean creditGPToPlayer(UUID playerUUID, long amount, String signature) {
        return gameServerBridge.creditGP(playerUUID, amount, signature);
    }

    /**
     * Records a deposit transaction in the database.
     */
    private void recordDeposit(UUID playerUUID, long amount, String signature) throws SQLException {
        String sql = """
            INSERT INTO blockchain_transactions
            (player_uuid, transaction_type, amount_gp, solana_signature, status, confirmed_at)
            VALUES (?, 'DEPOSIT', ?, ?, 'CONFIRMED', NOW())
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            stmt.setLong(2, amount);
            stmt.setString(3, signature);
            stmt.executeUpdate();
        }
    }

    /**
     * Stores an unlinked deposit for later claiming.
     */
    private void storeUnlinkedDeposit(String signature, BurnDetails details) throws SQLException {
        String sql = """
            INSERT INTO blockchain_transactions
            (player_uuid, transaction_type, amount_gp, solana_signature, status, error_message)
            VALUES (NULL, 'DEPOSIT', ?, ?, 'PENDING', ?)
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setLong(1, details.getAmount().longValue());
            stmt.setString(2, signature);
            stmt.setString(3, "Wallet not linked: " + details.getFromAddress());
            stmt.executeUpdate();
        }
        logger.info("Stored unlinked deposit from {} for {} tokens",
                details.getFromAddress(), details.getAmount());
    }

    /**
     * Creates a fraud alert for suspicious deposits.
     */
    private void createFraudAlert(UUID playerUUID, String signature, FraudDetector.RiskScore riskScore)
            throws SQLException {
        String sql = """
            INSERT INTO fraud_alerts
            (player_uuid, alert_type, severity, description, related_data)
            VALUES (?, 'SUSPICIOUS_DEPOSIT', ?, ?, ?::jsonb)
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            stmt.setString(2, riskScore.getLevel().name());
            stmt.setString(3, riskScore.getDescription());
            stmt.setString(4, String.format("{\"signature\":\"%s\",\"factors\":%s}",
                    signature, riskScore.getFactorsJson()));
            stmt.executeUpdate();
        }
    }

    /**
     * Event representing a token burn on-chain.
     */
    public static class BurnEvent {
        private final String signature;
        private final String fromAddress;
        private final BigInteger amount;
        private final long slot;

        public BurnEvent(String signature, String fromAddress, BigInteger amount, long slot) {
            this.signature = signature;
            this.fromAddress = fromAddress;
            this.amount = amount;
            this.slot = slot;
        }

        public String getSignature() { return signature; }
        public String getFromAddress() { return fromAddress; }
        public BigInteger getAmount() { return amount; }
        public long getSlot() { return slot; }
    }

    /**
     * Details of a verified burn transaction.
     */
    public static class BurnDetails {
        private final String fromAddress;
        private final BigInteger amount;
        private final long timestamp;

        public BurnDetails(String fromAddress, BigInteger amount, long timestamp) {
            this.fromAddress = fromAddress;
            this.amount = amount;
            this.timestamp = timestamp;
        }

        public String getFromAddress() { return fromAddress; }
        public BigInteger getAmount() { return amount; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * Interface for communicating with the game server.
     */
    public interface GameServerBridge {
        boolean creditGP(UUID playerUUID, long amount, String signature);
        void notifyDeposit(UUID playerUUID, long amount, String signature);
    }
}
