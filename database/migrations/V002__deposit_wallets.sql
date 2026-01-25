-- ChainScape Database Migration V002
-- Add deposit wallets for custodial deposit flow

-- Player deposit wallets table
-- Each player gets a unique deposit address for receiving CSGP tokens
CREATE TABLE player_deposit_wallets (
    id                      BIGSERIAL PRIMARY KEY,
    player_uuid             UUID NOT NULL UNIQUE,
    deposit_address         VARCHAR(44) NOT NULL UNIQUE,
    encrypted_private_key   BYTEA NOT NULL,
    is_active               BOOLEAN DEFAULT TRUE,
    created_at              TIMESTAMPTZ DEFAULT NOW(),
    last_sweep_at           TIMESTAMPTZ,
    total_deposited         BIGINT DEFAULT 0,

    CONSTRAINT valid_deposit_address CHECK (length(deposit_address) = 44)
);

-- Indexes for fast lookups
CREATE INDEX idx_deposit_wallets_player ON player_deposit_wallets(player_uuid);
CREATE INDEX idx_deposit_wallets_address ON player_deposit_wallets(deposit_address);
CREATE INDEX idx_deposit_wallets_active ON player_deposit_wallets(is_active) WHERE is_active = true;

-- Pending sweeps table (tracks in-progress sweep operations)
CREATE TABLE pending_sweeps (
    id                  BIGSERIAL PRIMARY KEY,
    deposit_address     VARCHAR(44) NOT NULL,
    player_uuid         UUID NOT NULL,
    token_amount        BIGINT NOT NULL,
    status              VARCHAR(20) DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'FUNDED', 'SWEPT', 'COMPLETED', 'FAILED')),
    funding_tx          VARCHAR(88),
    sweep_tx            VARCHAR(88),
    sol_return_tx       VARCHAR(88),
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    error_message       TEXT
);

CREATE INDEX idx_pending_sweeps_status ON pending_sweeps(status);
CREATE INDEX idx_pending_sweeps_address ON pending_sweeps(deposit_address);

-- Update player_wallets table to be optional (for withdrawals only)
-- Add comment clarifying its new purpose
COMMENT ON TABLE player_wallets IS 'Optional wallet linking for withdrawals. Players can withdraw to any verified wallet.';

-- Function to get or create deposit wallet
CREATE OR REPLACE FUNCTION get_player_deposit_address(p_player_uuid UUID)
RETURNS VARCHAR(44) AS $$
DECLARE
    v_address VARCHAR(44);
BEGIN
    SELECT deposit_address INTO v_address
    FROM player_deposit_wallets
    WHERE player_uuid = p_player_uuid AND is_active = true;

    RETURN v_address;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update total_deposited after successful deposit
CREATE OR REPLACE FUNCTION update_deposit_total()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.transaction_type = 'DEPOSIT' AND NEW.status = 'CONFIRMED' THEN
        UPDATE player_deposit_wallets
        SET total_deposited = total_deposited + NEW.amount_gp,
            last_sweep_at = NOW()
        WHERE player_uuid = NEW.player_uuid;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_deposit_total
    AFTER INSERT OR UPDATE ON blockchain_transactions
    FOR EACH ROW
    EXECUTE FUNCTION update_deposit_total();

-- Add comment
COMMENT ON TABLE player_deposit_wallets IS 'Custodial deposit wallets - each player gets a unique address to deposit CSGP tokens';
