package io.chainscape.bridge.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Fraud detection service for analyzing deposit and withdrawal patterns.
 * Uses rule-based scoring to flag suspicious activity.
 */
public class FraudDetector {
    private static final Logger logger = LoggerFactory.getLogger(FraudDetector.class);

    private final Connection dbConnection;

    // Risk thresholds
    private static final int LOW_RISK_THRESHOLD = 20;
    private static final int MEDIUM_RISK_THRESHOLD = 50;
    private static final int HIGH_RISK_THRESHOLD = 80;

    // Rule weights
    private static final int NEW_ACCOUNT_WEIGHT = 15;
    private static final int RAPID_WITHDRAWAL_WEIGHT = 20;
    private static final int LARGE_AMOUNT_WEIGHT = 25;
    private static final int UNUSUAL_PATTERN_WEIGHT = 30;
    private static final int LINKED_SUSPICIOUS_ACCOUNT_WEIGHT = 40;
    private static final int PREVIOUS_FRAUD_WEIGHT = 50;

    public FraudDetector(Connection dbConnection) {
        this.dbConnection = dbConnection;
    }

    /**
     * Evaluates the risk of a deposit transaction.
     */
    public RiskScore evaluateDeposit(UUID playerUUID, long amount, String walletAddress) {
        List<RiskFactor> factors = new ArrayList<>();
        int totalScore = 0;

        try {
            // Check for new account
            int accountAgeDays = getAccountAgeDays(playerUUID);
            if (accountAgeDays < 1) {
                factors.add(new RiskFactor("NEW_ACCOUNT", "Account created less than 24 hours ago", NEW_ACCOUNT_WEIGHT));
                totalScore += NEW_ACCOUNT_WEIGHT;
            } else if (accountAgeDays < 7) {
                factors.add(new RiskFactor("YOUNG_ACCOUNT", "Account less than 7 days old", NEW_ACCOUNT_WEIGHT / 2));
                totalScore += NEW_ACCOUNT_WEIGHT / 2;
            }

            // Check for large deposit amount
            if (amount >= 1_000_000_000) { // 1B GP
                factors.add(new RiskFactor("VERY_LARGE_DEPOSIT", "Deposit over 1B GP", LARGE_AMOUNT_WEIGHT));
                totalScore += LARGE_AMOUNT_WEIGHT;
            } else if (amount >= 100_000_000) { // 100M GP
                factors.add(new RiskFactor("LARGE_DEPOSIT", "Deposit over 100M GP", LARGE_AMOUNT_WEIGHT / 2));
                totalScore += LARGE_AMOUNT_WEIGHT / 2;
            }

            // Check if wallet has been used with flagged accounts
            if (isWalletLinkedToFraud(walletAddress)) {
                factors.add(new RiskFactor("SUSPICIOUS_WALLET", "Wallet previously linked to flagged account",
                        LINKED_SUSPICIOUS_ACCOUNT_WEIGHT));
                totalScore += LINKED_SUSPICIOUS_ACCOUNT_WEIGHT;
            }

            // Check player's previous fraud history
            int previousFlags = getPreviousFraudFlags(playerUUID);
            if (previousFlags > 0) {
                factors.add(new RiskFactor("PREVIOUS_FLAGS", "Player has " + previousFlags + " previous fraud flags",
                        Math.min(PREVIOUS_FRAUD_WEIGHT, previousFlags * 10)));
                totalScore += Math.min(PREVIOUS_FRAUD_WEIGHT, previousFlags * 10);
            }

        } catch (Exception e) {
            logger.error("Error evaluating deposit risk", e);
            factors.add(new RiskFactor("EVALUATION_ERROR", "Could not complete risk evaluation", 10));
            totalScore += 10;
        }

        return createRiskScore(totalScore, factors);
    }

    /**
     * Evaluates the risk of a withdrawal request.
     */
    public RiskScore evaluateWithdrawal(UUID playerUUID, long amount, String walletAddress) {
        List<RiskFactor> factors = new ArrayList<>();
        int totalScore = 0;

        try {
            // Check for new account
            int accountAgeDays = getAccountAgeDays(playerUUID);
            if (accountAgeDays < 1) {
                factors.add(new RiskFactor("NEW_ACCOUNT", "Account created less than 24 hours ago", NEW_ACCOUNT_WEIGHT));
                totalScore += NEW_ACCOUNT_WEIGHT;
            } else if (accountAgeDays < 7) {
                factors.add(new RiskFactor("YOUNG_ACCOUNT", "Account less than 7 days old", NEW_ACCOUNT_WEIGHT / 2));
                totalScore += NEW_ACCOUNT_WEIGHT / 2;
            }

            // Check for rapid withdrawal after deposit
            long recentDeposits = getRecentDepositAmount(playerUUID, 24);
            if (recentDeposits > 0 && amount > recentDeposits * 0.8) {
                factors.add(new RiskFactor("RAPID_WITHDRAWAL", "Withdrawing most of recent deposit within 24h",
                        RAPID_WITHDRAWAL_WEIGHT));
                totalScore += RAPID_WITHDRAWAL_WEIGHT;
            }

            // Check withdrawal amount vs account history
            long totalEarned = getTotalGPEarned(playerUUID);
            if (amount > totalEarned * 2) {
                factors.add(new RiskFactor("AMOUNT_VS_HISTORY", "Withdrawal exceeds 2x total GP earned",
                        UNUSUAL_PATTERN_WEIGHT));
                totalScore += UNUSUAL_PATTERN_WEIGHT;
            }

            // Check for large withdrawal
            if (amount >= 1_000_000_000) {
                factors.add(new RiskFactor("VERY_LARGE_WITHDRAWAL", "Withdrawal over 1B GP", LARGE_AMOUNT_WEIGHT));
                totalScore += LARGE_AMOUNT_WEIGHT;
            } else if (amount >= 100_000_000) {
                factors.add(new RiskFactor("LARGE_WITHDRAWAL", "Withdrawal over 100M GP", LARGE_AMOUNT_WEIGHT / 2));
                totalScore += LARGE_AMOUNT_WEIGHT / 2;
            }

            // Check if withdrawing to different wallet than deposit wallet
            String depositWallet = getMostRecentDepositWallet(playerUUID);
            if (depositWallet != null && !depositWallet.equals(walletAddress)) {
                factors.add(new RiskFactor("DIFFERENT_WALLET", "Withdrawing to different wallet than deposit source",
                        UNUSUAL_PATTERN_WEIGHT / 2));
                totalScore += UNUSUAL_PATTERN_WEIGHT / 2;
            }

            // Check for suspicious trade patterns (possible RWT indicator)
            if (hasSuspiciousTradePattern(playerUUID)) {
                factors.add(new RiskFactor("TRADE_PATTERN", "Suspicious trade patterns detected",
                        UNUSUAL_PATTERN_WEIGHT));
                totalScore += UNUSUAL_PATTERN_WEIGHT;
            }

            // Check player's previous fraud history
            int previousFlags = getPreviousFraudFlags(playerUUID);
            if (previousFlags > 0) {
                factors.add(new RiskFactor("PREVIOUS_FLAGS", "Player has " + previousFlags + " previous fraud flags",
                        Math.min(PREVIOUS_FRAUD_WEIGHT, previousFlags * 10)));
                totalScore += Math.min(PREVIOUS_FRAUD_WEIGHT, previousFlags * 10);
            }

            // Check if wallet linked to other suspicious accounts
            if (isWalletLinkedToFraud(walletAddress)) {
                factors.add(new RiskFactor("SUSPICIOUS_WALLET", "Wallet linked to flagged accounts",
                        LINKED_SUSPICIOUS_ACCOUNT_WEIGHT));
                totalScore += LINKED_SUSPICIOUS_ACCOUNT_WEIGHT;
            }

        } catch (Exception e) {
            logger.error("Error evaluating withdrawal risk", e);
            factors.add(new RiskFactor("EVALUATION_ERROR", "Could not complete risk evaluation", 10));
            totalScore += 10;
        }

        return createRiskScore(totalScore, factors);
    }

    /**
     * Creates a risk score from the total and factors.
     */
    private RiskScore createRiskScore(int totalScore, List<RiskFactor> factors) {
        RiskLevel level;
        if (totalScore >= HIGH_RISK_THRESHOLD) {
            level = RiskLevel.CRITICAL;
        } else if (totalScore >= MEDIUM_RISK_THRESHOLD) {
            level = RiskLevel.HIGH;
        } else if (totalScore >= LOW_RISK_THRESHOLD) {
            level = RiskLevel.MEDIUM;
        } else {
            level = RiskLevel.LOW;
        }

        String description = String.format("Risk score: %d (%s) - %d factors",
                totalScore, level.name(), factors.size());

        return new RiskScore(level, totalScore, description, factors);
    }

    /**
     * Gets the account age in days.
     */
    private int getAccountAgeDays(UUID playerUUID) throws SQLException {
        // This would query the game server's player table
        // For now, return a default
        return 30;
    }

    /**
     * Gets the total deposit amount in the last N hours.
     */
    private long getRecentDepositAmount(UUID playerUUID, int hours) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(amount_gp), 0) as total
            FROM blockchain_transactions
            WHERE player_uuid = ?
            AND transaction_type = 'DEPOSIT'
            AND status = 'CONFIRMED'
            AND created_at > NOW() - INTERVAL '%d hours'
            """.formatted(hours);

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("total");
            }
        }
        return 0;
    }

    /**
     * Gets the total GP earned through gameplay.
     */
    private long getTotalGPEarned(UUID playerUUID) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(amount), 0) as total
            FROM gp_audit_log
            WHERE player_uuid = ?
            AND change_type IN ('NPC_DROP', 'GE_SALE', 'QUEST_REWARD', 'MINIGAME_REWARD')
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("total");
            }
        }
        return 0;
    }

    /**
     * Gets the most recent deposit wallet address.
     */
    private String getMostRecentDepositWallet(UUID playerUUID) throws SQLException {
        String sql = """
            SELECT pw.solana_address
            FROM blockchain_transactions bt
            JOIN player_wallets pw ON bt.player_uuid = pw.player_uuid
            WHERE bt.player_uuid = ?
            AND bt.transaction_type = 'DEPOSIT'
            AND bt.status = 'CONFIRMED'
            ORDER BY bt.created_at DESC
            LIMIT 1
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("solana_address");
            }
        }
        return null;
    }

    /**
     * Checks if a wallet is linked to any flagged accounts.
     */
    private boolean isWalletLinkedToFraud(String walletAddress) throws SQLException {
        String sql = """
            SELECT COUNT(*) as count
            FROM fraud_alerts fa
            JOIN player_wallets pw ON fa.player_uuid = pw.player_uuid
            WHERE pw.solana_address = ?
            AND fa.status NOT IN ('FALSE_POSITIVE', 'RESOLVED')
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, walletAddress);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count") > 0;
            }
        }
        return false;
    }

    /**
     * Gets the count of previous fraud flags for a player.
     */
    private int getPreviousFraudFlags(UUID playerUUID) throws SQLException {
        String sql = """
            SELECT COUNT(*) as count
            FROM fraud_alerts
            WHERE player_uuid = ?
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count");
            }
        }
        return 0;
    }

    /**
     * Checks for suspicious trade patterns (possible RWT).
     */
    private boolean hasSuspiciousTradePattern(UUID playerUUID) throws SQLException {
        // Check for one-sided trades (receiving GP without giving items of value)
        String sql = """
            SELECT COUNT(*) as count
            FROM gp_audit_log
            WHERE player_uuid = ?
            AND change_type = 'TRADE_RECEIVED'
            AND amount > 10000000
            AND created_at > NOW() - INTERVAL '7 days'
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count") >= 3;
            }
        }
        return false;
    }

    /**
     * Risk levels.
     */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * Risk score result.
     */
    public static class RiskScore {
        private final RiskLevel level;
        private final int score;
        private final String description;
        private final List<RiskFactor> factors;

        public RiskScore(RiskLevel level, int score, String description, List<RiskFactor> factors) {
            this.level = level;
            this.score = score;
            this.description = description;
            this.factors = factors;
        }

        public RiskLevel getLevel() { return level; }
        public int getScore() { return score; }
        public String getDescription() { return description; }
        public List<RiskFactor> getFactors() { return factors; }

        public String getFactorsJson() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < factors.size(); i++) {
                if (i > 0) sb.append(",");
                RiskFactor f = factors.get(i);
                sb.append(String.format("{\"code\":\"%s\",\"description\":\"%s\",\"weight\":%d}",
                        f.code(), f.description(), f.weight()));
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * Individual risk factor.
     */
    public record RiskFactor(String code, String description, int weight) {}
}
