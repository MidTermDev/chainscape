package io.chainscape.common.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object for blockchain transactions.
 */
public record TransactionDTO(
        long id,
        UUID playerUUID,
        TransactionType type,
        long amountGP,
        String solanaSignature,
        TransactionStatus status,
        String errorMessage,
        Instant createdAt,
        Instant confirmedAt
) {
    public enum TransactionType {
        DEPOSIT,
        WITHDRAWAL
    }

    public enum TransactionStatus {
        PENDING,
        PROCESSING,
        CONFIRMED,
        FAILED,
        REVERSED
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long id;
        private UUID playerUUID;
        private TransactionType type;
        private long amountGP;
        private String solanaSignature;
        private TransactionStatus status = TransactionStatus.PENDING;
        private String errorMessage;
        private Instant createdAt = Instant.now();
        private Instant confirmedAt;

        public Builder id(long id) { this.id = id; return this; }
        public Builder playerUUID(UUID playerUUID) { this.playerUUID = playerUUID; return this; }
        public Builder type(TransactionType type) { this.type = type; return this; }
        public Builder amountGP(long amountGP) { this.amountGP = amountGP; return this; }
        public Builder solanaSignature(String solanaSignature) { this.solanaSignature = solanaSignature; return this; }
        public Builder status(TransactionStatus status) { this.status = status; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder confirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; return this; }

        public TransactionDTO build() {
            return new TransactionDTO(id, playerUUID, type, amountGP, solanaSignature,
                    status, errorMessage, createdAt, confirmedAt);
        }
    }
}
