package io.chainscape.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for blockchain-related events.
 */
public abstract sealed class BlockchainEvent
        permits DepositEvent, WithdrawalEvent, WalletLinkedEvent, ReconciliationEvent {

    private final String eventId;
    private final Instant timestamp;

    protected BlockchainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    public String getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }

    public abstract String getEventType();
    public abstract Map<String, Object> toMap();
}

/**
 * Event emitted when a deposit is credited.
 */
final class DepositEvent extends BlockchainEvent {
    private final UUID playerUUID;
    private final long amount;
    private final String solanaSignature;
    private final String fromAddress;

    public DepositEvent(UUID playerUUID, long amount, String solanaSignature, String fromAddress) {
        super();
        this.playerUUID = playerUUID;
        this.amount = amount;
        this.solanaSignature = solanaSignature;
        this.fromAddress = fromAddress;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public long getAmount() { return amount; }
    public String getSolanaSignature() { return solanaSignature; }
    public String getFromAddress() { return fromAddress; }

    @Override
    public String getEventType() { return "DEPOSIT"; }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                "eventId", getEventId(),
                "eventType", getEventType(),
                "timestamp", getTimestamp().toString(),
                "playerUUID", playerUUID.toString(),
                "amount", amount,
                "solanaSignature", solanaSignature,
                "fromAddress", fromAddress
        );
    }
}

/**
 * Event emitted when a withdrawal is processed.
 */
final class WithdrawalEvent extends BlockchainEvent {
    private final UUID playerUUID;
    private final long amount;
    private final String solanaSignature;
    private final String toAddress;
    private final WithdrawalStatus status;

    public enum WithdrawalStatus {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public WithdrawalEvent(UUID playerUUID, long amount, String solanaSignature,
                          String toAddress, WithdrawalStatus status) {
        super();
        this.playerUUID = playerUUID;
        this.amount = amount;
        this.solanaSignature = solanaSignature;
        this.toAddress = toAddress;
        this.status = status;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public long getAmount() { return amount; }
    public String getSolanaSignature() { return solanaSignature; }
    public String getToAddress() { return toAddress; }
    public WithdrawalStatus getStatus() { return status; }

    @Override
    public String getEventType() { return "WITHDRAWAL"; }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                "eventId", getEventId(),
                "eventType", getEventType(),
                "timestamp", getTimestamp().toString(),
                "playerUUID", playerUUID.toString(),
                "amount", amount,
                "solanaSignature", solanaSignature != null ? solanaSignature : "",
                "toAddress", toAddress,
                "status", status.name()
        );
    }
}

/**
 * Event emitted when a wallet is linked to an account.
 */
final class WalletLinkedEvent extends BlockchainEvent {
    private final UUID playerUUID;
    private final String solanaAddress;

    public WalletLinkedEvent(UUID playerUUID, String solanaAddress) {
        super();
        this.playerUUID = playerUUID;
        this.solanaAddress = solanaAddress;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public String getSolanaAddress() { return solanaAddress; }

    @Override
    public String getEventType() { return "WALLET_LINKED"; }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                "eventId", getEventId(),
                "eventType", getEventType(),
                "timestamp", getTimestamp().toString(),
                "playerUUID", playerUUID.toString(),
                "solanaAddress", solanaAddress
        );
    }
}

/**
 * Event emitted when reconciliation completes.
 */
final class ReconciliationEvent extends BlockchainEvent {
    private final long totalInGame;
    private final long totalOnChain;
    private final long discrepancy;
    private final String severity;

    public ReconciliationEvent(long totalInGame, long totalOnChain, long discrepancy, String severity) {
        super();
        this.totalInGame = totalInGame;
        this.totalOnChain = totalOnChain;
        this.discrepancy = discrepancy;
        this.severity = severity;
    }

    public long getTotalInGame() { return totalInGame; }
    public long getTotalOnChain() { return totalOnChain; }
    public long getDiscrepancy() { return discrepancy; }
    public String getSeverity() { return severity; }

    @Override
    public String getEventType() { return "RECONCILIATION"; }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                "eventId", getEventId(),
                "eventType", getEventType(),
                "timestamp", getTimestamp().toString(),
                "totalInGame", totalInGame,
                "totalOnChain", totalOnChain,
                "discrepancy", discrepancy,
                "severity", severity
        );
    }
}
