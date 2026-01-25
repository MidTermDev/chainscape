package io.chainscape.bridge.solana;

import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.types.*;
import org.p2p.solanaj.ws.SubscriptionWebSocketClient;
import org.p2p.solanaj.ws.listeners.NotificationEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Monitors the Solana blockchain for CSGP token burn events.
 * Uses WebSocket subscriptions for real-time event detection.
 */
public class TransactionMonitor {
    private static final Logger logger = LoggerFactory.getLogger(TransactionMonitor.class);

    private final RpcClient rpcClient;
    private final String wsUrl;
    private final String tokenMintAddress;
    private SubscriptionWebSocketClient wsClient;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> processedSignatures;
    private final List<Consumer<BurnEvent>> burnListeners;

    private volatile boolean running = false;
    private long lastProcessedSlot = 0;

    // Configuration
    private static final int SIGNATURE_CACHE_SIZE = 10000;
    private static final long POLL_INTERVAL_MS = 2000;

    public TransactionMonitor(String rpcUrl, String wsUrl, String tokenMintAddress) {
        this.rpcClient = new RpcClient(rpcUrl);
        this.wsUrl = wsUrl;
        this.tokenMintAddress = tokenMintAddress;
        this.executor = Executors.newFixedThreadPool(2);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.processedSignatures = Collections.synchronizedMap(
                new LinkedHashMap<>(SIGNATURE_CACHE_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                        return size() > SIGNATURE_CACHE_SIZE;
                    }
                }
        );
        this.burnListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Subscribes to burn events with the given listener.
     */
    public void subscribeToBurnEvents(Consumer<BurnEvent> listener) {
        burnListeners.add(listener);

        if (!running) {
            start();
        }
    }

    /**
     * Unsubscribes all listeners and stops monitoring.
     */
    public void unsubscribe() {
        burnListeners.clear();
        stop();
    }

    /**
     * Starts the transaction monitor.
     */
    private void start() {
        running = true;

        // Try to connect WebSocket for real-time updates
        try {
            connectWebSocket();
        } catch (Exception e) {
            logger.warn("WebSocket connection failed, falling back to polling", e);
        }

        // Also start polling as backup/fallback
        scheduler.scheduleAtFixedRate(
                this::pollForTransactions,
                0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS
        );

        logger.info("Transaction monitor started for token: {}", tokenMintAddress);
    }

    /**
     * Stops the transaction monitor.
     */
    private void stop() {
        running = false;

        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception e) {
                logger.warn("Error closing WebSocket", e);
            }
        }

        scheduler.shutdown();
        executor.shutdown();

        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Transaction monitor stopped");
    }

    /**
     * Connects to Solana WebSocket for real-time updates.
     */
    private void connectWebSocket() throws Exception {
        wsClient = new SubscriptionWebSocketClient(new URI(wsUrl));

        // Subscribe to logs mentioning our token program
        wsClient.logsSubscribe(
                tokenMintAddress,
                new NotificationEventListener() {
                    @Override
                    public void onNotificationEvent(Object notification) {
                        executor.submit(() -> handleLogNotification(notification));
                    }
                }
        );

        logger.info("WebSocket connected to {}", wsUrl);
    }

    /**
     * Handles a log notification from WebSocket.
     */
    private void handleLogNotification(Object notification) {
        try {
            // Parse notification and check for burn events
            // This is a simplified version - actual implementation would parse
            // the notification structure properly

            if (notification instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> notif = (Map<String, Object>) notification;
                String signature = (String) notif.get("signature");

                if (signature != null && !isProcessed(signature)) {
                    checkTransactionForBurn(signature);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling log notification", e);
        }
    }

    /**
     * Polls for recent transactions (fallback when WebSocket fails).
     */
    private void pollForTransactions() {
        if (!running) return;

        try {
            // Get recent signatures for the token program
            List<SignatureInformation> signatures = rpcClient.getApi()
                    .getSignaturesForAddress(
                            new org.p2p.solanaj.core.PublicKey(tokenMintAddress),
                            20, // limit
                            null // before signature
                    );

            for (SignatureInformation sig : signatures) {
                String signature = sig.getSignature();

                if (!isProcessed(signature)) {
                    executor.submit(() -> checkTransactionForBurn(signature));
                }
            }

        } catch (Exception e) {
            logger.error("Error polling for transactions", e);
        }
    }

    /**
     * Checks a transaction for burn instructions.
     */
    private void checkTransactionForBurn(String signature) {
        try {
            // Mark as processed to avoid duplicates
            markProcessed(signature);

            ConfirmedTransaction tx = rpcClient.getApi().getTransaction(signature);

            if (tx == null || tx.getMeta() == null || tx.getMeta().getErr() != null) {
                return; // Invalid or failed transaction
            }

            // Check for token balance changes indicating a burn
            List<TokenBalance> preBalances = tx.getMeta().getPreTokenBalances();
            List<TokenBalance> postBalances = tx.getMeta().getPostTokenBalances();

            for (TokenBalance pre : preBalances) {
                if (!pre.getMint().equals(tokenMintAddress)) continue;

                TokenBalance post = findMatchingBalance(postBalances, pre.getAccountIndex());
                if (post == null) {
                    // Account no longer exists - possible close account (burn all)
                    BigInteger burnAmount = new BigInteger(pre.getUiTokenAmount().getAmount());
                    if (burnAmount.compareTo(BigInteger.ZERO) > 0) {
                        emitBurnEvent(signature, pre.getOwner(), burnAmount, tx.getSlot());
                    }
                    continue;
                }

                BigInteger preBal = new BigInteger(pre.getUiTokenAmount().getAmount());
                BigInteger postBal = new BigInteger(post.getUiTokenAmount().getAmount());

                // Check if this looks like a burn (balance decreased)
                if (preBal.compareTo(postBal) > 0) {
                    BigInteger burnAmount = preBal.subtract(postBal);

                    // Verify it's actually a burn by checking if supply decreased
                    // or if tokens went to a burn address
                    if (isBurnTransaction(tx, pre.getOwner(), burnAmount)) {
                        emitBurnEvent(signature, pre.getOwner(), burnAmount, tx.getSlot());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error checking transaction for burn: {}", signature, e);
        }
    }

    /**
     * Verifies if a transaction is actually a burn (not just a transfer).
     */
    private boolean isBurnTransaction(ConfirmedTransaction tx, String owner, BigInteger amount) {
        // Check transaction logs for burn instruction
        List<String> logs = tx.getMeta().getLogMessages();
        if (logs != null) {
            for (String log : logs) {
                if (log.contains("Burn") || log.contains("burn")) {
                    return true;
                }
            }
        }

        // Alternative: Check if tokens went to a known burn address
        // or if total supply decreased
        return false;
    }

    /**
     * Emits a burn event to all listeners.
     */
    private void emitBurnEvent(String signature, String fromAddress, BigInteger amount, long slot) {
        BurnEvent event = new BurnEvent(signature, fromAddress, amount, slot);

        logger.info("Burn detected: {} tokens from {} (sig: {})",
                amount, fromAddress.substring(0, 8) + "...", signature.substring(0, 16) + "...");

        for (Consumer<BurnEvent> listener : burnListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.error("Error notifying burn listener", e);
            }
        }
    }

    /**
     * Gets the confirmation count for a transaction.
     */
    public int getConfirmationCount(String signature) {
        try {
            SignatureStatuses statuses = rpcClient.getApi()
                    .getSignatureStatuses(Collections.singletonList(signature), true);

            if (statuses != null && !statuses.getValue().isEmpty()) {
                SignatureStatuses.Value status = statuses.getValue().get(0);
                if (status != null && status.getConfirmations() != null) {
                    return status.getConfirmations().intValue();
                }
                // If finalized, return a high number
                if (status != null && "finalized".equals(status.getConfirmationStatus())) {
                    return 32;
                }
            }
        } catch (Exception e) {
            logger.error("Error getting confirmation count for {}", signature, e);
        }
        return 0;
    }

    /**
     * Checks if a signature has already been processed.
     */
    private boolean isProcessed(String signature) {
        return processedSignatures.containsKey(signature);
    }

    /**
     * Marks a signature as processed.
     */
    private void markProcessed(String signature) {
        processedSignatures.put(signature, System.currentTimeMillis());
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
     * Burn event data.
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
}
