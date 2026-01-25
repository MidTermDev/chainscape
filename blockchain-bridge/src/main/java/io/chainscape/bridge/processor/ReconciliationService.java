package io.chainscape.bridge.processor;

import io.chainscape.bridge.solana.TokenOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.sql.*;
import java.util.concurrent.*;

/**
 * Reconciliation service that verifies in-game GP supply matches on-chain token supply.
 * Runs hourly and triggers alerts if discrepancies are detected.
 */
public class ReconciliationService {
    private static final Logger logger = LoggerFactory.getLogger(ReconciliationService.class);

    private final TokenOperations tokenOperations;
    private final Connection dbConnection;
    private final GameGPQueryService gpQueryService;
    private final ScheduledExecutorService scheduler;

    // Thresholds
    private static final long MINOR_DISCREPANCY_THRESHOLD = 1_000_000L; // 1M GP
    private static final long MAJOR_DISCREPANCY_THRESHOLD = 100_000_000L; // 100M GP
    private static final long CRITICAL_DISCREPANCY_THRESHOLD = 1_000_000_000L; // 1B GP

    private volatile boolean running = false;

    public ReconciliationService(
            TokenOperations tokenOperations,
            Connection dbConnection,
            GameGPQueryService gpQueryService) {
        this.tokenOperations = tokenOperations;
        this.dbConnection = dbConnection;
        this.gpQueryService = gpQueryService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Starts the reconciliation service with hourly checks.
     */
    public void start() {
        running = true;

        // Run initial reconciliation
        scheduler.submit(this::runReconciliation);

        // Schedule hourly reconciliation
        scheduler.scheduleAtFixedRate(
                this::runReconciliation,
                1, 1, TimeUnit.HOURS);

        logger.info("Reconciliation service started (hourly schedule)");
    }

    /**
     * Stops the reconciliation service.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Reconciliation service stopped");
    }

    /**
     * Runs a reconciliation check.
     */
    public ReconciliationResult runReconciliation() {
        logger.info("Starting reconciliation check...");

        try {
            // Get on-chain token supply
            BigInteger onChainSupply = tokenOperations.getTotalSupply();
            long totalTokens = onChainSupply.longValue();

            // Get in-game GP totals
            GPTotals gpTotals = gpQueryService.getGPTotals();
            long totalInGame = gpTotals.total();

            // Get total burned (should always be matched by deposits)
            long totalBurned = getTotalBurnedFromDB();

            // Calculate discrepancy
            // Formula: In-game GP should equal (minted - burned) which equals current supply
            long discrepancy = totalInGame - totalTokens;
            long absoluteDiscrepancy = Math.abs(discrepancy);

            // Determine severity
            DiscrepancySeverity severity = determineServerity(absoluteDiscrepancy);

            // Save snapshot
            long snapshotId = saveSnapshot(
                    totalInGame,
                    gpTotals.inInventories(),
                    gpTotals.inBanks(),
                    gpTotals.inGE(),
                    totalTokens,
                    totalBurned,
                    discrepancy
            );

            ReconciliationResult result = new ReconciliationResult(
                    snapshotId,
                    totalInGame,
                    totalTokens,
                    discrepancy,
                    severity,
                    System.currentTimeMillis()
            );

            // Handle based on severity
            handleDiscrepancy(result);

            logger.info("Reconciliation complete: in-game={}, on-chain={}, discrepancy={} ({})",
                    formatGP(totalInGame), formatGP(totalTokens), formatGP(discrepancy), severity);

            return result;

        } catch (Exception e) {
            logger.error("Reconciliation failed", e);
            return new ReconciliationResult(
                    -1, 0, 0, 0,
                    DiscrepancySeverity.ERROR,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * Determines the severity level of a discrepancy.
     */
    private DiscrepancySeverity determineServerity(long absoluteDiscrepancy) {
        if (absoluteDiscrepancy == 0) {
            return DiscrepancySeverity.NONE;
        } else if (absoluteDiscrepancy < MINOR_DISCREPANCY_THRESHOLD) {
            return DiscrepancySeverity.MINOR;
        } else if (absoluteDiscrepancy < MAJOR_DISCREPANCY_THRESHOLD) {
            return DiscrepancySeverity.MAJOR;
        } else if (absoluteDiscrepancy < CRITICAL_DISCREPANCY_THRESHOLD) {
            return DiscrepancySeverity.SEVERE;
        } else {
            return DiscrepancySeverity.CRITICAL;
        }
    }

    /**
     * Handles a discrepancy based on its severity.
     */
    private void handleDiscrepancy(ReconciliationResult result) throws SQLException {
        switch (result.severity()) {
            case NONE:
            case MINOR:
                // Normal operation
                break;

            case MAJOR:
                // Log warning and create alert
                logger.warn("Major discrepancy detected: {} GP", result.discrepancy());
                createAlert("MAJOR_DISCREPANCY", "MEDIUM",
                        String.format("Discrepancy of %s GP detected", formatGP(result.discrepancy())),
                        result);
                break;

            case SEVERE:
                // Log error and create high-priority alert
                logger.error("Severe discrepancy detected: {} GP", result.discrepancy());
                createAlert("SEVERE_DISCREPANCY", "HIGH",
                        String.format("Severe discrepancy of %s GP detected", formatGP(result.discrepancy())),
                        result);
                break;

            case CRITICAL:
                // Critical - trigger circuit breaker
                logger.error("CRITICAL discrepancy detected: {} GP - TRIGGERING CIRCUIT BREAKER",
                        result.discrepancy());
                createAlert("CRITICAL_DISCREPANCY", "CRITICAL",
                        String.format("CRITICAL discrepancy of %s GP - circuit breaker triggered",
                                formatGP(result.discrepancy())),
                        result);
                triggerCircuitBreaker();
                break;

            case ERROR:
                // Reconciliation failed - create alert
                createAlert("RECONCILIATION_ERROR", "HIGH",
                        "Reconciliation check failed to complete", result);
                break;
        }
    }

    /**
     * Saves a reconciliation snapshot to the database.
     */
    private long saveSnapshot(
            long totalInGame, long inInventories, long inBanks, long inGE,
            long totalTokens, long totalBurned, long discrepancy) throws SQLException {

        String sql = """
            INSERT INTO reconciliation_snapshots
            (total_gp_in_game, total_gp_in_banks, total_gp_in_ge, total_tokens_supply, total_burned, discrepancy)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setLong(1, totalInGame);
            stmt.setLong(2, inBanks);
            stmt.setLong(3, inGE);
            stmt.setLong(4, totalTokens);
            stmt.setLong(5, totalBurned);
            stmt.setLong(6, discrepancy);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("id");
            }
        }
        return -1;
    }

    /**
     * Gets total burned tokens from the database.
     */
    private long getTotalBurnedFromDB() throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(amount_gp), 0) as total
            FROM blockchain_transactions
            WHERE transaction_type = 'DEPOSIT' AND status = 'CONFIRMED'
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("total");
            }
        }
        return 0;
    }

    /**
     * Creates an alert for discrepancies.
     */
    private void createAlert(String alertType, String severity, String description,
                             ReconciliationResult result) throws SQLException {
        String sql = """
            INSERT INTO fraud_alerts
            (player_uuid, alert_type, severity, description, related_data)
            VALUES (NULL, ?, ?, ?, ?::jsonb)
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, alertType);
            stmt.setString(2, severity);
            stmt.setString(3, description);
            stmt.setString(4, String.format(
                    "{\"snapshotId\":%d,\"inGame\":%d,\"onChain\":%d,\"discrepancy\":%d}",
                    result.snapshotId(), result.totalInGame(), result.totalOnChain(), result.discrepancy()));
            stmt.executeUpdate();
        }
    }

    /**
     * Triggers the circuit breaker to disable withdrawals.
     */
    private void triggerCircuitBreaker() throws SQLException {
        String sql = """
            UPDATE circuit_breaker_state
            SET is_open = true, opened_at = NOW(), notes = 'Triggered by reconciliation discrepancy'
            WHERE breaker_name = 'withdrawals'
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
        logger.error("CIRCUIT BREAKER TRIGGERED - Withdrawals are now disabled");
    }

    /**
     * Manually resets the circuit breaker (admin action).
     */
    public void resetCircuitBreaker(String adminNote) throws SQLException {
        String sql = """
            UPDATE circuit_breaker_state
            SET is_open = false, opened_at = NULL, notes = ?
            WHERE breaker_name = 'withdrawals'
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, "Reset by admin: " + adminNote);
            stmt.executeUpdate();
        }
        logger.info("Circuit breaker reset by admin: {}", adminNote);
    }

    /**
     * Gets the latest reconciliation result.
     */
    public ReconciliationResult getLatestSnapshot() throws SQLException {
        String sql = """
            SELECT id, total_gp_in_game, total_tokens_supply, discrepancy, snapshot_time
            FROM reconciliation_snapshots
            ORDER BY snapshot_time DESC
            LIMIT 1
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long discrepancy = rs.getLong("discrepancy");
                return new ReconciliationResult(
                        rs.getLong("id"),
                        rs.getLong("total_gp_in_game"),
                        rs.getLong("total_tokens_supply"),
                        discrepancy,
                        determineServerity(Math.abs(discrepancy)),
                        rs.getTimestamp("snapshot_time").getTime()
                );
            }
        }
        return null;
    }

    private String formatGP(long amount) {
        if (Math.abs(amount) >= 1_000_000_000) {
            return String.format("%.2fB", amount / 1_000_000_000.0);
        } else if (Math.abs(amount) >= 1_000_000) {
            return String.format("%.2fM", amount / 1_000_000.0);
        } else if (Math.abs(amount) >= 1_000) {
            return String.format("%.2fK", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }

    /**
     * Result of a reconciliation check.
     */
    public record ReconciliationResult(
            long snapshotId,
            long totalInGame,
            long totalOnChain,
            long discrepancy,
            DiscrepancySeverity severity,
            long timestamp
    ) {}

    /**
     * GP totals from the game.
     */
    public record GPTotals(
            long inInventories,
            long inBanks,
            long inGE
    ) {
        public long total() {
            return inInventories + inBanks + inGE;
        }
    }

    /**
     * Severity levels for discrepancies.
     */
    public enum DiscrepancySeverity {
        NONE,
        MINOR,
        MAJOR,
        SEVERE,
        CRITICAL,
        ERROR
    }

    /**
     * Interface for querying game GP totals.
     */
    public interface GameGPQueryService {
        GPTotals getGPTotals();
    }
}
