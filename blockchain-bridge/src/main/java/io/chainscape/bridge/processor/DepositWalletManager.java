package io.chainscape.bridge.processor;

import io.chainscape.bridge.solana.TokenOperations;
import org.p2p.solanaj.core.*;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.types.AccountInfo;
import org.p2p.solanaj.programs.TokenProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages unique deposit wallets for each user.
 *
 * Flow:
 * 1. User requests deposit address -> generate new Solana keypair
 * 2. User sends CSGP tokens to their deposit address
 * 3. Monitor detects incoming tokens
 * 4. Sweep process:
 *    a. Send 0.001 SOL from treasury to deposit wallet (for tx fees)
 *    b. Transfer all CSGP tokens to treasury
 *    c. Transfer remaining SOL back to treasury
 * 5. Credit GP to user's in-game account
 */
public class DepositWalletManager {
    private static final Logger logger = LoggerFactory.getLogger(DepositWalletManager.class);

    private final RpcClient rpcClient;
    private final Connection dbConnection;
    private final PublicKey tokenMint;
    private final Account treasuryAccount;
    private final PublicKey treasuryTokenAccount;
    private final GameServerBridge gameServerBridge;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService sweepExecutor;

    // Configuration
    private static final long SWEEP_CHECK_INTERVAL_MS = 5000; // Check every 5 seconds
    private static final long FUNDING_AMOUNT_LAMPORTS = 1_000_000; // 0.001 SOL
    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final long MIN_SWEEP_AMOUNT = 1; // Minimum token units to trigger sweep

    private volatile boolean running = false;

    public DepositWalletManager(
            RpcClient rpcClient,
            Connection dbConnection,
            String tokenMintAddress,
            Account treasuryAccount,
            String treasuryTokenAccountAddress,
            GameServerBridge gameServerBridge) {
        this.rpcClient = rpcClient;
        this.dbConnection = dbConnection;
        this.tokenMint = new PublicKey(tokenMintAddress);
        this.treasuryAccount = treasuryAccount;
        this.treasuryTokenAccount = new PublicKey(treasuryTokenAccountAddress);
        this.gameServerBridge = gameServerBridge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.sweepExecutor = Executors.newFixedThreadPool(4);
    }

    /**
     * Starts the deposit wallet monitoring service.
     */
    public void start() {
        running = true;
        scheduler.scheduleAtFixedRate(
                this::checkAllDepositWallets,
                0, SWEEP_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
        logger.info("Deposit wallet manager started");
    }

    /**
     * Stops the service.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        sweepExecutor.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            sweepExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Deposit wallet manager stopped");
    }

    /**
     * Gets or creates a deposit wallet for a user.
     *
     * @param playerUUID The player's UUID
     * @return The deposit wallet address
     */
    public String getOrCreateDepositWallet(UUID playerUUID) throws SQLException {
        // Check if user already has a deposit wallet
        String existingAddress = getExistingDepositWallet(playerUUID);
        if (existingAddress != null) {
            return existingAddress;
        }

        // Generate new keypair
        Account newWallet = new Account();
        String publicKey = newWallet.getPublicKey().toBase58();
        byte[] privateKey = newWallet.getSecretKey();

        // Store in database (private key encrypted)
        storeDepositWallet(playerUUID, publicKey, privateKey);

        logger.info("Created deposit wallet for player {}: {}", playerUUID, publicKey);
        return publicKey;
    }

    /**
     * Checks all active deposit wallets for incoming tokens.
     */
    private void checkAllDepositWallets() {
        if (!running) return;

        try {
            List<DepositWalletInfo> wallets = getActiveDepositWallets();

            for (DepositWalletInfo wallet : wallets) {
                sweepExecutor.submit(() -> checkAndSweepWallet(wallet));
            }
        } catch (Exception e) {
            logger.error("Error checking deposit wallets", e);
        }
    }

    /**
     * Checks a single wallet for tokens and sweeps if found.
     */
    private void checkAndSweepWallet(DepositWalletInfo wallet) {
        try {
            // Get token balance
            BigInteger tokenBalance = getTokenBalance(wallet.address);

            if (tokenBalance.compareTo(BigInteger.valueOf(MIN_SWEEP_AMOUNT)) >= 0) {
                logger.info("Deposit detected: {} tokens in wallet {} for player {}",
                        tokenBalance, wallet.address, wallet.playerUUID);

                // Execute sweep
                boolean success = sweepDeposit(wallet, tokenBalance);

                if (success) {
                    // Convert to GP and credit
                    long gpAmount = TokenOperations.tokenUnitsToGp(tokenBalance);

                    // Record transaction
                    recordDeposit(wallet.playerUUID, gpAmount, wallet.address);

                    // Credit to game
                    gameServerBridge.creditGP(wallet.playerUUID, gpAmount, wallet.address);

                    logger.info("Deposit completed: {} GP credited to player {}",
                            gpAmount, wallet.playerUUID);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing wallet {}: {}", wallet.address, e.getMessage());
        }
    }

    /**
     * Executes the sweep process for a deposit.
     */
    private boolean sweepDeposit(DepositWalletInfo wallet, BigInteger tokenAmount) {
        try {
            Account depositAccount = loadWalletAccount(wallet);
            PublicKey depositPubkey = depositAccount.getPublicKey();

            // Step 1: Fund the deposit wallet with SOL for transaction fees
            logger.debug("Funding deposit wallet with {} lamports", FUNDING_AMOUNT_LAMPORTS);
            String fundingTx = fundWallet(depositPubkey);
            if (fundingTx == null) {
                logger.error("Failed to fund deposit wallet");
                return false;
            }
            waitForConfirmation(fundingTx);

            // Step 2: Get or create token account for deposit wallet
            PublicKey depositTokenAccount = getOrCreateTokenAccount(depositAccount);

            // Step 3: Transfer tokens to treasury
            logger.debug("Sweeping {} tokens to treasury", tokenAmount);
            String tokenTx = transferTokens(depositAccount, depositTokenAccount,
                    treasuryTokenAccount, tokenAmount);
            if (tokenTx == null) {
                logger.error("Failed to sweep tokens");
                // Try to recover SOL anyway
                sweepSol(depositAccount);
                return false;
            }
            waitForConfirmation(tokenTx);

            // Step 4: Sweep remaining SOL back to treasury
            logger.debug("Sweeping SOL back to treasury");
            sweepSol(depositAccount);

            return true;

        } catch (Exception e) {
            logger.error("Sweep failed for wallet {}", wallet.address, e);
            return false;
        }
    }

    /**
     * Funds a deposit wallet with SOL from treasury.
     */
    private String fundWallet(PublicKey destination) {
        try {
            Transaction tx = new Transaction();
            tx.addInstruction(
                    SystemProgram.transfer(
                            treasuryAccount.getPublicKey(),
                            destination,
                            FUNDING_AMOUNT_LAMPORTS
                    )
            );

            String blockhash = rpcClient.getApi().getRecentBlockhash();
            tx.setRecentBlockHash(blockhash);
            tx.sign(treasuryAccount);

            return rpcClient.getApi().sendTransaction(tx, treasuryAccount);
        } catch (Exception e) {
            logger.error("Failed to fund wallet", e);
            return null;
        }
    }

    /**
     * Transfers tokens from deposit wallet to treasury.
     */
    private String transferTokens(Account from, PublicKey fromTokenAccount,
                                   PublicKey toTokenAccount, BigInteger amount) {
        try {
            Transaction tx = new Transaction();
            tx.addInstruction(
                    TokenProgram.transfer(
                            fromTokenAccount,
                            toTokenAccount,
                            from.getPublicKey(),
                            amount.longValue()
                    )
            );

            String blockhash = rpcClient.getApi().getRecentBlockhash();
            tx.setRecentBlockHash(blockhash);
            tx.sign(from);

            return rpcClient.getApi().sendTransaction(tx, from);
        } catch (Exception e) {
            logger.error("Failed to transfer tokens", e);
            return null;
        }
    }

    /**
     * Sweeps remaining SOL from deposit wallet back to treasury.
     */
    private void sweepSol(Account depositAccount) {
        try {
            long balance = rpcClient.getApi().getBalance(depositAccount.getPublicKey());

            // Leave minimum for rent exemption or if too low
            long sweepAmount = balance - 5000; // Keep 5000 lamports for fees
            if (sweepAmount <= 0) {
                return;
            }

            Transaction tx = new Transaction();
            tx.addInstruction(
                    SystemProgram.transfer(
                            depositAccount.getPublicKey(),
                            treasuryAccount.getPublicKey(),
                            sweepAmount
                    )
            );

            String blockhash = rpcClient.getApi().getRecentBlockhash();
            tx.setRecentBlockHash(blockhash);
            tx.sign(depositAccount);

            rpcClient.getApi().sendTransaction(tx, depositAccount);
        } catch (Exception e) {
            logger.error("Failed to sweep SOL", e);
        }
    }

    /**
     * Gets token balance for a wallet.
     */
    private BigInteger getTokenBalance(String walletAddress) {
        try {
            PublicKey wallet = new PublicKey(walletAddress);
            PublicKey tokenAccount = findAssociatedTokenAddress(wallet);

            var balance = rpcClient.getApi().getTokenAccountBalance(tokenAccount);
            if (balance != null && balance.getValue() != null) {
                return new BigInteger(balance.getValue().getAmount());
            }
        } catch (Exception e) {
            // Token account might not exist yet
        }
        return BigInteger.ZERO;
    }

    /**
     * Gets or creates the associated token account for a wallet.
     */
    private PublicKey getOrCreateTokenAccount(Account wallet) throws Exception {
        PublicKey ata = findAssociatedTokenAddress(wallet.getPublicKey());

        // Check if exists
        AccountInfo info = rpcClient.getApi().getAccountInfo(ata);
        if (info == null || info.getValue() == null) {
            // Create ATA
            createAssociatedTokenAccount(wallet);
        }

        return ata;
    }

    /**
     * Creates an associated token account.
     */
    private void createAssociatedTokenAccount(Account wallet) throws Exception {
        PublicKey ata = findAssociatedTokenAddress(wallet.getPublicKey());
        PublicKey ataProgramId = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

        Transaction tx = new Transaction();
        tx.addInstruction(new TransactionInstruction(
                ataProgramId,
                Arrays.asList(
                        new AccountMeta(wallet.getPublicKey(), true, true),
                        new AccountMeta(ata, false, true),
                        new AccountMeta(wallet.getPublicKey(), false, false),
                        new AccountMeta(tokenMint, false, false),
                        new AccountMeta(SystemProgram.PROGRAM_ID, false, false),
                        new AccountMeta(TokenProgram.PROGRAM_ID, false, false)
                ),
                new byte[0]
        ));

        String blockhash = rpcClient.getApi().getRecentBlockhash();
        tx.setRecentBlockHash(blockhash);
        tx.sign(wallet);

        String sig = rpcClient.getApi().sendTransaction(tx, wallet);
        waitForConfirmation(sig);
    }

    /**
     * Finds the associated token address for a wallet.
     */
    private PublicKey findAssociatedTokenAddress(PublicKey wallet) {
        try {
            PublicKey ataProgramId = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
            return PublicKey.findProgramAddress(
                    Arrays.asList(
                            wallet.toByteArray(),
                            TokenProgram.PROGRAM_ID.toByteArray(),
                            tokenMint.toByteArray()
                    ),
                    ataProgramId
            ).getAddress();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive ATA", e);
        }
    }

    /**
     * Waits for transaction confirmation.
     */
    private void waitForConfirmation(String signature) throws InterruptedException {
        int maxWait = 30;
        for (int i = 0; i < maxWait; i++) {
            try {
                var statuses = rpcClient.getApi().getSignatureStatuses(
                        Collections.singletonList(signature), true);
                if (statuses != null && !statuses.getValue().isEmpty()) {
                    var status = statuses.getValue().get(0);
                    if (status != null && status.getConfirmations() != null
                            && status.getConfirmations() >= REQUIRED_CONFIRMATIONS) {
                        return;
                    }
                    if (status != null && "finalized".equals(status.getConfirmationStatus())) {
                        return;
                    }
                }
            } catch (Exception e) {
                // Continue waiting
            }
            Thread.sleep(1000);
        }
    }

    // Database methods

    private String getExistingDepositWallet(UUID playerUUID) throws SQLException {
        String sql = "SELECT deposit_address FROM player_deposit_wallets WHERE player_uuid = ? AND is_active = true";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("deposit_address");
            }
        }
        return null;
    }

    private void storeDepositWallet(UUID playerUUID, String address, byte[] privateKey) throws SQLException {
        String sql = """
            INSERT INTO player_deposit_wallets (player_uuid, deposit_address, encrypted_private_key, created_at)
            VALUES (?, ?, ?, NOW())
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            stmt.setString(2, address);
            stmt.setBytes(3, encryptPrivateKey(privateKey)); // Should use proper encryption
            stmt.executeUpdate();
        }
    }

    private List<DepositWalletInfo> getActiveDepositWallets() throws SQLException {
        String sql = "SELECT player_uuid, deposit_address FROM player_deposit_wallets WHERE is_active = true";
        List<DepositWalletInfo> wallets = new ArrayList<>();
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                wallets.add(new DepositWalletInfo(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("deposit_address")
                ));
            }
        }
        return wallets;
    }

    private Account loadWalletAccount(DepositWalletInfo wallet) throws SQLException {
        String sql = "SELECT encrypted_private_key FROM player_deposit_wallets WHERE deposit_address = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, wallet.address);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] encryptedKey = rs.getBytes("encrypted_private_key");
                byte[] privateKey = decryptPrivateKey(encryptedKey);
                return new Account(privateKey);
            }
        }
        throw new RuntimeException("Wallet not found: " + wallet.address);
    }

    private void recordDeposit(UUID playerUUID, long gpAmount, String depositAddress) throws SQLException {
        String sql = """
            INSERT INTO blockchain_transactions
            (player_uuid, transaction_type, amount_gp, status, confirmed_at, error_message)
            VALUES (?, 'DEPOSIT', ?, 'CONFIRMED', NOW(), ?)
            """;
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setObject(1, playerUUID);
            stmt.setLong(2, gpAmount);
            stmt.setString(3, "Deposit to " + depositAddress);
            stmt.executeUpdate();
        }
    }

    // Encryption helpers (should use proper encryption in production)
    private byte[] encryptPrivateKey(byte[] privateKey) {
        // TODO: Implement proper encryption (AES-256-GCM with HSM-stored key)
        return privateKey;
    }

    private byte[] decryptPrivateKey(byte[] encrypted) {
        // TODO: Implement proper decryption
        return encrypted;
    }

    /**
     * Info about a deposit wallet.
     */
    private record DepositWalletInfo(UUID playerUUID, String address) {}

    /**
     * Interface for game server communication.
     */
    public interface GameServerBridge {
        void creditGP(UUID playerUUID, long amount, String source);
    }
}
