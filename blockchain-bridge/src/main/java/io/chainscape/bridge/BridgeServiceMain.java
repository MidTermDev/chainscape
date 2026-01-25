package io.chainscape.bridge;

import io.chainscape.bridge.processor.DepositProcessor;
import io.chainscape.bridge.processor.ReconciliationService;
import io.chainscape.bridge.processor.WithdrawalProcessor;
import io.chainscape.bridge.security.FraudDetector;
import io.chainscape.bridge.security.RateLimiter;
import io.chainscape.bridge.solana.TokenOperations;
import io.chainscape.bridge.solana.TransactionMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;

/**
 * Main entry point for the ChainScape Blockchain Bridge Service.
 * Coordinates deposit processing, withdrawal handling, and reconciliation.
 */
public class BridgeServiceMain {
    private static final Logger logger = LoggerFactory.getLogger(BridgeServiceMain.class);

    private final HikariDataSource dataSource;
    private final TokenOperations tokenOperations;
    private final TransactionMonitor transactionMonitor;
    private final DepositProcessor depositProcessor;
    private final WithdrawalProcessor withdrawalProcessor;
    private final ReconciliationService reconciliationService;

    public BridgeServiceMain() {
        // Load configuration from environment
        String dbHost = getEnvOrDefault("DB_HOST", "localhost");
        String dbPort = getEnvOrDefault("DB_PORT", "5432");
        String dbUser = getEnvOrDefault("DB_USER", "chainscape");
        String dbPassword = getEnvOrDefault("DB_PASSWORD", "chainscape");
        String dbName = getEnvOrDefault("DB_NAME", "chainscape");

        String solanaRpcUrl = getEnvOrDefault("SOLANA_RPC_URL", "https://api.devnet.solana.com");
        String solanaWsUrl = getEnvOrDefault("SOLANA_WS_URL", "wss://api.devnet.solana.com");
        String solanaBackupRpcUrl = System.getenv("SOLANA_BACKUP_RPC_URL");
        String tokenMintAddress = requireEnv("TOKEN_MINT_ADDRESS");
        String mintAuthorityKey = requireEnv("MINT_AUTHORITY_KEY");

        // Initialize database connection pool
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s", dbHost, dbPort, dbName));
        hikariConfig.setUsername(dbUser);
        hikariConfig.setPassword(dbPassword);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(hikariConfig);

        // Initialize Solana components
        this.tokenOperations = new TokenOperations(
                solanaRpcUrl,
                solanaBackupRpcUrl,
                tokenMintAddress,
                mintAuthorityKey
        );

        this.transactionMonitor = new TransactionMonitor(
                solanaRpcUrl,
                solanaWsUrl,
                tokenMintAddress
        );

        // Initialize security components
        Connection dbConnection = getConnection();
        FraudDetector fraudDetector = new FraudDetector(dbConnection);
        RateLimiter rateLimiter = new RateLimiter(dbConnection);

        // Initialize processors
        this.depositProcessor = new DepositProcessor(
                tokenOperations,
                transactionMonitor,
                fraudDetector,
                dbConnection,
                createGameServerBridge()
        );

        this.withdrawalProcessor = new WithdrawalProcessor(
                tokenOperations,
                rateLimiter,
                fraudDetector,
                dbConnection
        );

        this.reconciliationService = new ReconciliationService(
                tokenOperations,
                dbConnection,
                createGPQueryService()
        );

        logger.info("Bridge service initialized");
    }

    public void start() {
        logger.info("Starting ChainScape Bridge Service...");

        // Start all processors
        depositProcessor.start();
        withdrawalProcessor.start();
        reconciliationService.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        logger.info("Bridge service started successfully");
        logger.info("Monitoring token: {}", System.getenv("TOKEN_MINT_ADDRESS"));
    }

    public void stop() {
        logger.info("Shutting down Bridge Service...");

        depositProcessor.stop();
        withdrawalProcessor.stop();
        reconciliationService.stop();
        dataSource.close();

        logger.info("Bridge service stopped");
    }

    private Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get database connection", e);
        }
    }

    private DepositProcessor.GameServerBridge createGameServerBridge() {
        // In production, this would communicate with the game server via RPC or messaging
        return new DepositProcessor.GameServerBridge() {
            @Override
            public boolean creditGP(java.util.UUID playerUUID, long amount, String signature) {
                logger.info("Crediting {} GP to player {} (signature: {})",
                        amount, playerUUID, signature.substring(0, 16) + "...");
                // TODO: Implement game server communication
                return true;
            }

            @Override
            public void notifyDeposit(java.util.UUID playerUUID, long amount, String signature) {
                logger.info("Notifying player {} of deposit: {} GP", playerUUID, amount);
                // TODO: Send notification to game server
            }
        };
    }

    private ReconciliationService.GameGPQueryService createGPQueryService() {
        // In production, this would query the game server for GP totals
        return () -> {
            logger.debug("Querying GP totals from game server");
            // TODO: Implement game server query
            return new ReconciliationService.GPTotals(0, 0, 0);
        };
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Required environment variable not set: " + key);
        }
        return value;
    }

    public static void main(String[] args) {
        try {
            BridgeServiceMain service = new BridgeServiceMain();
            service.start();

            // Keep the main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("Fatal error in Bridge Service", e);
            System.exit(1);
        }
    }
}
