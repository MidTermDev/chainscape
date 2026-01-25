package io.chainscape.bridge.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Rate limiter for withdrawal requests.
 * Implements per-player limits to prevent abuse.
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    private final Connection dbConnection;

    // Rate limit configuration
    private static final int MAX_WITHDRAWALS_PER_HOUR = 5;
    private static final int MAX_WITHDRAWALS_PER_DAY = 20;
    private static final long MAX_AMOUNT_PER_DAY = 2_147_483_647L; // Max int (2.147B GP)
    private static final long LARGE_WITHDRAWAL_THRESHOLD = 100_000_000L; // 100M GP
    private static final int MAX_LARGE_WITHDRAWALS_PER_DAY = 3;

    // In-memory cache for fast lookups (synced with DB)
    private final ConcurrentHashMap<UUID, PlayerRateLimitState> cache;

    public RateLimiter(Connection dbConnection) {
        this.dbConnection = dbConnection;
        this.cache = new ConcurrentHashMap<>();

        // Schedule periodic cache cleanup
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                this::cleanupCache,
                1, 1, TimeUnit.HOURS
        );
    }

    /**
     * Checks if a withdrawal is allowed under rate limits.
     *
     * @param playerUUID The player requesting withdrawal
     * @param amount The withdrawal amount
     * @return true if allowed, false if rate limited
     */
    public boolean checkWithdrawalLimit(UUID playerUUID, long amount) {
        try {
            // Get current state from cache or DB
            PlayerRateLimitState state = getPlayerState(playerUUID);

            // Check hourly limit
            if (state.withdrawalsInLastHour >= MAX_WITHDRAWALS_PER_HOUR) {
                logger.info("Rate limit hit: hourly limit for player {}", playerUUID);
                return false;
            }

            // Check daily limit
            if (state.withdrawalsInLastDay >= MAX_WITHDRAWALS_PER_DAY) {
                logger.info("Rate limit hit: daily limit for player {}", playerUUID);
                return false;
            }

            // Check daily amount limit
            if (state.amountInLastDay + amount > MAX_AMOUNT_PER_DAY) {
                logger.info("Rate limit hit: daily amount limit for player {}", playerUUID);
                return false;
            }

            // Check large withdrawal limit
            if (amount >= LARGE_WITHDRAWAL_THRESHOLD) {
                if (state.largeWithdrawalsInLastDay >= MAX_LARGE_WITHDRAWALS_PER_DAY) {
                    logger.info("Rate limit hit: large withdrawal limit for player {}", playerUUID);
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            logger.error("Error checking rate limit", e);
            return false; // Fail safe - deny on error
        }
    }

    /**
     * Records a withdrawal for rate limiting purposes.
     *
     * @param playerUUID The player making the withdrawal
     * @param amount The withdrawal amount
     */
    public void recordWithdrawal(UUID playerUUID, long amount) {
        try {
            // Record in database
            String sql = """
                INSERT INTO withdrawal_rate_limits (player_uuid, window_start, request_count, total_amount)
                VALUES (?, date_trunc('hour', NOW()), 1, ?)
                ON CONFLICT (player_uuid, window_start)
                DO UPDATE SET
                    request_count = withdrawal_rate_limits.request_count + 1,
                    total_amount = withdrawal_rate_limits.total_amount + ?
                """;

            try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
                stmt.setObject(1, playerUUID);
                stmt.setLong(2, amount);
                stmt.setLong(3, amount);
                stmt.executeUpdate();
            }

            // Update cache
            PlayerRateLimitState state = cache.computeIfAbsent(playerUUID, k -> new PlayerRateLimitState());
            state.withdrawalsInLastHour++;
            state.withdrawalsInLastDay++;
            state.amountInLastDay += amount;
            if (amount >= LARGE_WITHDRAWAL_THRESHOLD) {
                state.largeWithdrawalsInLastDay++;
            }
            state.lastUpdated = System.currentTimeMillis();

        } catch (Exception e) {
            logger.error("Error recording withdrawal for rate limit", e);
        }
    }

    /**
     * Gets the rate limit status for a player.
     */
    public RateLimitStatus getStatus(UUID playerUUID) {
        try {
            PlayerRateLimitState state = getPlayerState(playerUUID);

            return new RateLimitStatus(
                    MAX_WITHDRAWALS_PER_HOUR - state.withdrawalsInLastHour,
                    MAX_WITHDRAWALS_PER_DAY - state.withdrawalsInLastDay,
                    MAX_AMOUNT_PER_DAY - state.amountInLastDay,
                    state.nextHourReset,
                    state.nextDayReset
            );

        } catch (Exception e) {
            logger.error("Error getting rate limit status", e);
            return new RateLimitStatus(0, 0, 0, 0, 0);
        }
    }

    /**
     * Gets the current rate limit state for a player.
     */
    private PlayerRateLimitState getPlayerState(UUID playerUUID) throws SQLException {
        // Check cache first
        PlayerRateLimitState cached = cache.get(playerUUID);
        if (cached != null && !cached.isStale()) {
            return cached;
        }

        // Query database
        PlayerRateLimitState state = new PlayerRateLimitState();

        // Get hourly stats
        String hourlySql = """
            SELECT COALESCE(SUM(request_count), 0) as count, COALESCE(SUM(total_amount), 0) as amount
            FROM withdrawal_rate_limits
            WHERE player_uuid = ? AND window_start > NOW() - INTERVAL '1 hour'
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(hourlySql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                state.withdrawalsInLastHour = rs.getInt("count");
            }
        }

        // Get daily stats
        String dailySql = """
            SELECT COALESCE(SUM(request_count), 0) as count, COALESCE(SUM(total_amount), 0) as amount
            FROM withdrawal_rate_limits
            WHERE player_uuid = ? AND window_start > NOW() - INTERVAL '24 hours'
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(dailySql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                state.withdrawalsInLastDay = rs.getInt("count");
                state.amountInLastDay = rs.getLong("amount");
            }
        }

        // Get large withdrawal count
        String largeSql = """
            SELECT COUNT(*) as count
            FROM blockchain_transactions
            WHERE player_uuid = ?
            AND transaction_type = 'WITHDRAWAL'
            AND amount_gp >= ?
            AND created_at > NOW() - INTERVAL '24 hours'
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(largeSql)) {
            stmt.setObject(1, playerUUID);
            stmt.setLong(2, LARGE_WITHDRAWAL_THRESHOLD);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                state.largeWithdrawalsInLastDay = rs.getInt("count");
            }
        }

        // Calculate reset times
        long now = System.currentTimeMillis();
        state.nextHourReset = (now / 3600000 + 1) * 3600000; // Next hour
        state.nextDayReset = (now / 86400000 + 1) * 86400000; // Next day
        state.lastUpdated = now;

        // Update cache
        cache.put(playerUUID, state);

        return state;
    }

    /**
     * Cleans up stale cache entries.
     */
    private void cleanupCache() {
        long cutoff = System.currentTimeMillis() - 3600000; // 1 hour
        cache.entrySet().removeIf(entry -> entry.getValue().lastUpdated < cutoff);
    }

    /**
     * Resets rate limits for a player (admin action).
     */
    public void resetLimits(UUID playerUUID) throws SQLException {
        String sql = "DELETE FROM withdrawal_rate_limits WHERE player_uuid = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            stmt.executeUpdate();
        }
        cache.remove(playerUUID);
        logger.info("Rate limits reset for player {}", playerUUID);
    }

    /**
     * Internal state for a player's rate limits.
     */
    private static class PlayerRateLimitState {
        int withdrawalsInLastHour = 0;
        int withdrawalsInLastDay = 0;
        long amountInLastDay = 0;
        int largeWithdrawalsInLastDay = 0;
        long nextHourReset = 0;
        long nextDayReset = 0;
        long lastUpdated = 0;

        boolean isStale() {
            return System.currentTimeMillis() - lastUpdated > 60000; // 1 minute cache
        }
    }

    /**
     * Rate limit status for display to users.
     */
    public record RateLimitStatus(
            int remainingHourly,
            int remainingDaily,
            long remainingAmountDaily,
            long nextHourReset,
            long nextDayReset
    ) {}
}
