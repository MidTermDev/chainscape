-- ChainScape Database Schema
-- Version: 1.0.0
-- Description: Initial schema for blockchain-integrated OSRS private server

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Player wallet linkage table
-- Stores the connection between game accounts and Solana wallets
CREATE TABLE player_wallets (
    id              BIGSERIAL PRIMARY KEY,
    player_uuid     UUID NOT NULL UNIQUE,
    solana_address  VARCHAR(44) NOT NULL,
    verification_sig VARCHAR(88) NOT NULL,
    challenge_nonce VARCHAR(64),
    linked_at       TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    is_active       BOOLEAN DEFAULT TRUE,

    CONSTRAINT unique_solana_address UNIQUE (solana_address)
);

-- Index for fast lookups by Solana address
CREATE INDEX idx_player_wallets_solana_address ON player_wallets(solana_address);
CREATE INDEX idx_player_wallets_player_uuid ON player_wallets(player_uuid);

-- Blockchain transaction log
-- Records all deposits and withdrawals for audit and reconciliation
CREATE TABLE blockchain_transactions (
    id                BIGSERIAL PRIMARY KEY,
    player_uuid       UUID NOT NULL,
    transaction_type  VARCHAR(20) NOT NULL CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL')),
    amount_gp         BIGINT NOT NULL CHECK (amount_gp > 0),
    solana_signature  VARCHAR(88),
    status            VARCHAR(20) DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'PROCESSING', 'CONFIRMED', 'FAILED', 'REVERSED')),
    error_message     TEXT,
    created_at        TIMESTAMPTZ DEFAULT NOW(),
    confirmed_at      TIMESTAMPTZ,
    retry_count       INT DEFAULT 0,

    CONSTRAINT unique_solana_signature UNIQUE (solana_signature)
);

-- Indexes for transaction queries
CREATE INDEX idx_blockchain_tx_player ON blockchain_transactions(player_uuid);
CREATE INDEX idx_blockchain_tx_status ON blockchain_transactions(status);
CREATE INDEX idx_blockchain_tx_created ON blockchain_transactions(created_at);
CREATE INDEX idx_blockchain_tx_signature ON blockchain_transactions(solana_signature);

-- Reconciliation snapshots
-- Hourly snapshots comparing in-game GP supply with on-chain token supply
CREATE TABLE reconciliation_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    total_gp_in_game    BIGINT NOT NULL,
    total_gp_in_banks   BIGINT NOT NULL,
    total_gp_in_ge      BIGINT NOT NULL,
    total_tokens_supply BIGINT NOT NULL,
    total_burned        BIGINT NOT NULL DEFAULT 0,
    discrepancy         BIGINT DEFAULT 0,
    snapshot_time       TIMESTAMPTZ DEFAULT NOW(),
    notes               TEXT,

    CONSTRAINT positive_totals CHECK (
        total_gp_in_game >= 0 AND
        total_gp_in_banks >= 0 AND
        total_gp_in_ge >= 0 AND
        total_tokens_supply >= 0
    )
);

CREATE INDEX idx_reconciliation_time ON reconciliation_snapshots(snapshot_time);

-- Rate limiting table
-- Tracks withdrawal requests per player for rate limiting
CREATE TABLE withdrawal_rate_limits (
    id              BIGSERIAL PRIMARY KEY,
    player_uuid     UUID NOT NULL,
    window_start    TIMESTAMPTZ NOT NULL,
    request_count   INT DEFAULT 1,
    total_amount    BIGINT DEFAULT 0,

    CONSTRAINT unique_player_window UNIQUE (player_uuid, window_start)
);

CREATE INDEX idx_rate_limits_player ON withdrawal_rate_limits(player_uuid);
CREATE INDEX idx_rate_limits_window ON withdrawal_rate_limits(window_start);

-- GP change audit log
-- Records all significant GP changes in-game for fraud detection
CREATE TABLE gp_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    player_uuid     UUID NOT NULL,
    change_type     VARCHAR(30) NOT NULL CHECK (change_type IN (
        'DEPOSIT', 'WITHDRAWAL', 'TRADE_SENT', 'TRADE_RECEIVED',
        'GE_PURCHASE', 'GE_SALE', 'SHOP_PURCHASE', 'SHOP_SALE',
        'PICKUP', 'DROP', 'DEATH', 'PVP_KILL', 'NPC_DROP',
        'QUEST_REWARD', 'ADMIN_GRANT', 'ADMIN_REMOVE'
    )),
    amount          BIGINT NOT NULL,
    balance_before  BIGINT NOT NULL,
    balance_after   BIGINT NOT NULL,
    related_player  UUID,
    details         JSONB,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_gp_audit_player ON gp_audit_log(player_uuid);
CREATE INDEX idx_gp_audit_type ON gp_audit_log(change_type);
CREATE INDEX idx_gp_audit_created ON gp_audit_log(created_at);
CREATE INDEX idx_gp_audit_amount ON gp_audit_log(amount) WHERE amount >= 10000000;

-- Fraud detection alerts
-- Stores suspicious activity alerts for manual review
CREATE TABLE fraud_alerts (
    id              BIGSERIAL PRIMARY KEY,
    player_uuid     UUID NOT NULL,
    alert_type      VARCHAR(50) NOT NULL,
    severity        VARCHAR(10) CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    description     TEXT NOT NULL,
    related_data    JSONB,
    status          VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE')),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     VARCHAR(100),
    resolution_notes TEXT
);

CREATE INDEX idx_fraud_alerts_player ON fraud_alerts(player_uuid);
CREATE INDEX idx_fraud_alerts_status ON fraud_alerts(status);
CREATE INDEX idx_fraud_alerts_severity ON fraud_alerts(severity);

-- Pending mint queue
-- Queue for withdrawal mints awaiting multisig approval
CREATE TABLE pending_mints (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  BIGINT NOT NULL REFERENCES blockchain_transactions(id),
    player_uuid     UUID NOT NULL,
    solana_address  VARCHAR(44) NOT NULL,
    amount          BIGINT NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    expires_at      TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '24 hours'),
    status          VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    approved_by     TEXT[],
    rejection_reason TEXT
);

CREATE INDEX idx_pending_mints_status ON pending_mints(status);
CREATE INDEX idx_pending_mints_expires ON pending_mints(expires_at);

-- Circuit breaker state
-- Tracks system health and circuit breaker status
CREATE TABLE circuit_breaker_state (
    id              BIGSERIAL PRIMARY KEY,
    breaker_name    VARCHAR(50) NOT NULL UNIQUE,
    is_open         BOOLEAN DEFAULT FALSE,
    opened_at       TIMESTAMPTZ,
    failure_count   INT DEFAULT 0,
    last_failure    TIMESTAMPTZ,
    last_success    TIMESTAMPTZ,
    notes           TEXT
);

-- Insert default circuit breakers
INSERT INTO circuit_breaker_state (breaker_name) VALUES
    ('deposits'),
    ('withdrawals'),
    ('reconciliation');

-- Function to update timestamp on modification
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Trigger for player_wallets updated_at
CREATE TRIGGER update_player_wallets_updated_at
    BEFORE UPDATE ON player_wallets
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Function to check withdrawal rate limits
CREATE OR REPLACE FUNCTION check_withdrawal_rate_limit(
    p_player_uuid UUID,
    p_amount BIGINT
) RETURNS BOOLEAN AS $$
DECLARE
    hourly_count INT;
    daily_count INT;
    daily_amount BIGINT;
BEGIN
    -- Check hourly limit (5 per hour)
    SELECT COALESCE(SUM(request_count), 0) INTO hourly_count
    FROM withdrawal_rate_limits
    WHERE player_uuid = p_player_uuid
    AND window_start > NOW() - INTERVAL '1 hour';

    IF hourly_count >= 5 THEN
        RETURN FALSE;
    END IF;

    -- Check daily limit (20 per day)
    SELECT COALESCE(SUM(request_count), 0), COALESCE(SUM(total_amount), 0)
    INTO daily_count, daily_amount
    FROM withdrawal_rate_limits
    WHERE player_uuid = p_player_uuid
    AND window_start > NOW() - INTERVAL '24 hours';

    IF daily_count >= 20 THEN
        RETURN FALSE;
    END IF;

    -- Check daily amount limit (max 2.147B GP per day - max int value)
    IF daily_amount + p_amount > 2147483647 THEN
        RETURN FALSE;
    END IF;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Function to record withdrawal rate limit
CREATE OR REPLACE FUNCTION record_withdrawal_request(
    p_player_uuid UUID,
    p_amount BIGINT
) RETURNS VOID AS $$
BEGIN
    INSERT INTO withdrawal_rate_limits (player_uuid, window_start, request_count, total_amount)
    VALUES (p_player_uuid, date_trunc('hour', NOW()), 1, p_amount)
    ON CONFLICT (player_uuid, window_start)
    DO UPDATE SET
        request_count = withdrawal_rate_limits.request_count + 1,
        total_amount = withdrawal_rate_limits.total_amount + p_amount;
END;
$$ LANGUAGE plpgsql;

-- View for current player GP totals
CREATE VIEW player_gp_summary AS
SELECT
    player_uuid,
    SUM(CASE WHEN change_type IN ('DEPOSIT', 'TRADE_RECEIVED', 'GE_SALE', 'SHOP_SALE', 'PICKUP', 'NPC_DROP', 'QUEST_REWARD', 'PVP_KILL', 'ADMIN_GRANT') THEN amount ELSE 0 END) as total_gained,
    SUM(CASE WHEN change_type IN ('WITHDRAWAL', 'TRADE_SENT', 'GE_PURCHASE', 'SHOP_PURCHASE', 'DROP', 'DEATH', 'ADMIN_REMOVE') THEN amount ELSE 0 END) as total_lost,
    MAX(created_at) as last_activity
FROM gp_audit_log
GROUP BY player_uuid;

-- Comment on tables
COMMENT ON TABLE player_wallets IS 'Links game player accounts to their verified Solana wallet addresses';
COMMENT ON TABLE blockchain_transactions IS 'Audit log of all deposits (burns) and withdrawals (mints)';
COMMENT ON TABLE reconciliation_snapshots IS 'Hourly snapshots for verifying GP supply matches token supply';
COMMENT ON TABLE gp_audit_log IS 'Detailed log of all GP changes for fraud detection';
COMMENT ON TABLE fraud_alerts IS 'Suspicious activity alerts requiring manual review';
COMMENT ON TABLE pending_mints IS 'Queue for withdrawal mint requests awaiting multisig approval';
COMMENT ON TABLE circuit_breaker_state IS 'System health monitoring and automatic failsafe state';
