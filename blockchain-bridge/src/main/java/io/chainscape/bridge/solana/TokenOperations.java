package io.chainscape.bridge.solana;

import org.p2p.solanaj.core.*;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.p2p.solanaj.rpc.types.*;
import org.p2p.solanaj.programs.TokenProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles Solana SPL Token operations for CSGP token.
 * Includes minting (withdrawals) and burn verification (deposits).
 */
public class TokenOperations {
    private static final Logger logger = LoggerFactory.getLogger(TokenOperations.class);

    private final RpcClient rpcClient;
    private final RpcClient backupRpcClient;
    private final PublicKey tokenMint;
    private final PublicKey mintAuthority;
    private final Account mintAuthorityAccount;

    // Configuration
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final int PRIORITY_FEE_MICROLAMPORTS = 10000; // 0.00001 SOL per CU

    public TokenOperations(
            String rpcUrl,
            String backupRpcUrl,
            String tokenMintAddress,
            String mintAuthorityPrivateKey) {

        this.rpcClient = new RpcClient(rpcUrl);
        this.backupRpcClient = backupRpcUrl != null ? new RpcClient(backupRpcUrl) : null;
        this.tokenMint = new PublicKey(tokenMintAddress);

        // Load mint authority from private key
        byte[] privateKeyBytes = Base58.decode(mintAuthorityPrivateKey);
        this.mintAuthorityAccount = new Account(privateKeyBytes);
        this.mintAuthority = mintAuthorityAccount.getPublicKey();

        logger.info("TokenOperations initialized for mint: {}", tokenMintAddress);
    }

    /**
     * Mints tokens to a destination wallet.
     * Used for withdrawal processing.
     *
     * @param destinationWallet The Solana wallet to receive tokens
     * @param amount The amount of tokens to mint (1 token = 1 GP)
     * @return The transaction signature, or null if failed
     */
    public String mintTokens(String destinationWallet, BigInteger amount) {
        PublicKey destination = new PublicKey(destinationWallet);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // Get or create associated token account
                PublicKey destinationATA = findAssociatedTokenAddress(destination);

                // Build mint instruction
                Transaction transaction = new Transaction();

                // Add priority fee for faster confirmation
                transaction.addInstruction(
                        createComputeBudgetInstruction(PRIORITY_FEE_MICROLAMPORTS)
                );

                // Check if ATA exists, create if not
                if (!accountExists(destinationATA)) {
                    transaction.addInstruction(
                            createAssociatedTokenAccountInstruction(destination, destinationATA)
                    );
                }

                // Add mint instruction
                transaction.addInstruction(
                        TokenProgram.mintTo(
                                tokenMint,
                                destinationATA,
                                mintAuthority,
                                amount.longValue()
                        )
                );

                // Get recent blockhash
                String blockhash = getRecentBlockhash();
                transaction.setRecentBlockHash(blockhash);

                // Sign transaction
                transaction.sign(mintAuthorityAccount);

                // Send transaction
                String signature = sendTransaction(transaction);

                if (signature != null) {
                    // Wait for confirmation
                    boolean confirmed = waitForConfirmation(signature);
                    if (confirmed) {
                        logger.info("Mint successful: {} tokens to {} (sig: {})",
                                amount, destinationWallet.substring(0, 8) + "...",
                                signature.substring(0, 16) + "...");
                        return signature;
                    }
                }

                logger.warn("Mint attempt {} failed, retrying...", attempt);
                Thread.sleep(RETRY_DELAY_MS * attempt);

            } catch (Exception e) {
                logger.error("Mint attempt {} failed: {}", attempt, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        logger.error("Failed to mint tokens after {} attempts", MAX_RETRIES);
        return null;
    }

    /**
     * Verifies a burn transaction and returns the details.
     *
     * @param signature The transaction signature to verify
     * @return Burn details if valid, null otherwise
     */
    public BurnDetails verifyBurnTransaction(String signature) {
        try {
            ConfirmedTransaction tx = rpcClient.getApi().getTransaction(signature);

            if (tx == null || tx.getMeta() == null) {
                logger.warn("Transaction not found or no metadata: {}", signature);
                return null;
            }

            // Check if transaction was successful
            if (tx.getMeta().getErr() != null) {
                logger.warn("Transaction failed: {}", signature);
                return null;
            }

            // Parse the transaction to find burn instruction
            // This is a simplified version - actual implementation would parse
            // the inner instructions to find the burn

            List<String> accountKeys = tx.getTransaction().getMessage().getAccountKeys();
            List<TokenBalance> preTokenBalances = tx.getMeta().getPreTokenBalances();
            List<TokenBalance> postTokenBalances = tx.getMeta().getPostTokenBalances();

            // Find the account that had a balance decrease
            for (int i = 0; i < preTokenBalances.size(); i++) {
                TokenBalance pre = preTokenBalances.get(i);
                TokenBalance post = findMatchingBalance(postTokenBalances, pre.getAccountIndex());

                if (post != null && pre.getMint().equals(tokenMint.toBase58())) {
                    BigInteger preBal = new BigInteger(pre.getUiTokenAmount().getAmount());
                    BigInteger postBal = new BigInteger(post.getUiTokenAmount().getAmount());

                    if (preBal.compareTo(postBal) > 0) {
                        BigInteger burnAmount = preBal.subtract(postBal);
                        String owner = pre.getOwner();

                        return new BurnDetails(
                                owner,
                                burnAmount,
                                tx.getBlockTime() != null ? tx.getBlockTime() * 1000 : System.currentTimeMillis()
                        );
                    }
                }
            }

            logger.warn("No burn detected in transaction: {}", signature);
            return null;

        } catch (Exception e) {
            logger.error("Failed to verify burn transaction: {}", signature, e);
            return null;
        }
    }

    /**
     * Gets the total supply of CSGP tokens.
     */
    public BigInteger getTotalSupply() {
        try {
            // Get mint account info
            AccountInfo mintInfo = rpcClient.getApi().getAccountInfo(tokenMint);

            if (mintInfo != null && mintInfo.getValue() != null) {
                byte[] data = Base64.getDecoder().decode(mintInfo.getValue().getData().get(0));

                // Parse mint data - supply is at offset 36, 8 bytes little-endian
                if (data.length >= 44) {
                    long supply = 0;
                    for (int i = 0; i < 8; i++) {
                        supply |= ((long) (data[36 + i] & 0xFF)) << (8 * i);
                    }
                    return BigInteger.valueOf(supply);
                }
            }

            logger.error("Failed to get token supply");
            return BigInteger.ZERO;

        } catch (Exception e) {
            logger.error("Error getting token supply", e);
            return BigInteger.ZERO;
        }
    }

    /**
     * Gets the token balance for a wallet.
     */
    public BigInteger getBalance(String walletAddress) {
        try {
            PublicKey wallet = new PublicKey(walletAddress);
            PublicKey ata = findAssociatedTokenAddress(wallet);

            TokenAccountInfo accountInfo = rpcClient.getApi().getTokenAccountBalance(ata);
            if (accountInfo != null) {
                return new BigInteger(accountInfo.getValue().getAmount());
            }
            return BigInteger.ZERO;

        } catch (Exception e) {
            logger.debug("Failed to get balance for {}: {}", walletAddress, e.getMessage());
            return BigInteger.ZERO;
        }
    }

    /**
     * Finds the associated token address for a wallet.
     */
    private PublicKey findAssociatedTokenAddress(PublicKey wallet) {
        // SPL Associated Token Account Program
        PublicKey associatedTokenProgram = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

        try {
            byte[] seeds = new byte[96];
            System.arraycopy(wallet.toByteArray(), 0, seeds, 0, 32);
            System.arraycopy(TokenProgram.PROGRAM_ID.toByteArray(), 0, seeds, 32, 32);
            System.arraycopy(tokenMint.toByteArray(), 0, seeds, 64, 32);

            return PublicKey.findProgramAddress(
                    Arrays.asList(
                            wallet.toByteArray(),
                            TokenProgram.PROGRAM_ID.toByteArray(),
                            tokenMint.toByteArray()
                    ),
                    associatedTokenProgram
            ).getAddress();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive ATA", e);
        }
    }

    /**
     * Checks if an account exists on-chain.
     */
    private boolean accountExists(PublicKey account) {
        try {
            AccountInfo info = rpcClient.getApi().getAccountInfo(account);
            return info != null && info.getValue() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets a recent blockhash.
     */
    private String getRecentBlockhash() throws RpcException {
        return rpcClient.getApi().getRecentBlockhash();
    }

    /**
     * Sends a transaction and returns the signature.
     */
    private String sendTransaction(Transaction transaction) {
        try {
            return rpcClient.getApi().sendTransaction(transaction, mintAuthorityAccount);
        } catch (RpcException e) {
            if (backupRpcClient != null) {
                try {
                    logger.warn("Primary RPC failed, trying backup");
                    return backupRpcClient.getApi().sendTransaction(transaction, mintAuthorityAccount);
                } catch (RpcException backupError) {
                    logger.error("Backup RPC also failed", backupError);
                }
            }
            logger.error("Failed to send transaction", e);
            return null;
        }
    }

    /**
     * Waits for transaction confirmation.
     */
    private boolean waitForConfirmation(String signature) {
        int maxWaitSeconds = 30;
        int waited = 0;

        while (waited < maxWaitSeconds) {
            try {
                SignatureStatuses statuses = rpcClient.getApi().getSignatureStatuses(
                        Collections.singletonList(signature), true);

                if (statuses != null && !statuses.getValue().isEmpty()) {
                    SignatureStatuses.Value status = statuses.getValue().get(0);
                    if (status != null && status.getConfirmationStatus() != null) {
                        String confirmationStatus = status.getConfirmationStatus();
                        if ("finalized".equals(confirmationStatus) || "confirmed".equals(confirmationStatus)) {
                            return true;
                        }
                    }
                }

                Thread.sleep(500);
                waited++;

            } catch (Exception e) {
                logger.warn("Error checking confirmation status", e);
            }
        }

        return false;
    }

    /**
     * Creates a compute budget instruction for priority fees.
     */
    private TransactionInstruction createComputeBudgetInstruction(int microLamports) {
        // ComputeBudgetProgram.SetComputeUnitPrice
        PublicKey computeBudgetProgram = new PublicKey("ComputeBudget111111111111111111111111111111");

        byte[] data = new byte[9];
        data[0] = 3; // SetComputeUnitPrice instruction
        // Encode microLamports as u64 little-endian
        for (int i = 0; i < 8; i++) {
            data[1 + i] = (byte) ((microLamports >> (8 * i)) & 0xFF);
        }

        return new TransactionInstruction(
                computeBudgetProgram,
                Collections.emptyList(),
                data
        );
    }

    /**
     * Creates an associated token account instruction.
     */
    private TransactionInstruction createAssociatedTokenAccountInstruction(
            PublicKey owner, PublicKey ata) {

        PublicKey associatedTokenProgram = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
        PublicKey systemProgram = new PublicKey("11111111111111111111111111111111");
        PublicKey rentSysvar = new PublicKey("SysvarRent111111111111111111111111111111111");

        return new TransactionInstruction(
                associatedTokenProgram,
                Arrays.asList(
                        new AccountMeta(mintAuthority, true, true), // payer
                        new AccountMeta(ata, false, true), // associated token account
                        new AccountMeta(owner, false, false), // owner
                        new AccountMeta(tokenMint, false, false), // mint
                        new AccountMeta(systemProgram, false, false),
                        new AccountMeta(TokenProgram.PROGRAM_ID, false, false),
                        new AccountMeta(rentSysvar, false, false)
                ),
                new byte[0] // Create instruction has no data
        );
    }

    private TokenBalance findMatchingBalance(List<TokenBalance> balances, int accountIndex) {
        for (TokenBalance balance : balances) {
            if (balance.getAccountIndex() == accountIndex) {
                return balance;
            }
        }
        return null;
    }

    /**
     * Details of a verified burn transaction.
     */
    public record BurnDetails(
            String fromAddress,
            BigInteger amount,
            long timestamp
    ) {}
}
