import React, { useState, useEffect, useCallback } from 'react';
import { useWallet } from '@solana/wallet-adapter-react';
import { WalletMultiButton } from '@solana/wallet-adapter-react-ui';
import { useGameStore } from '../store/gameStore';
import { getWebSocketClient } from '../network/WebSocketClient';
import { ClientOpcode } from '../network/PacketHandler';

type TransactionMode = 'deposit' | 'withdraw' | null;

export const WalletPanel: React.FC = () => {
  const { publicKey, connected } = useWallet();
  const { wallet, player, addPendingTransaction } = useGameStore();
  const [mode, setMode] = useState<TransactionMode>(null);
  const [amount, setAmount] = useState('');
  const [status, setStatus] = useState<{ type: 'success' | 'error' | 'pending'; message: string } | null>(null);
  const [depositAddress, setDepositAddress] = useState<string | null>(null);
  const [isLoadingAddress, setIsLoadingAddress] = useState(false);
  const [copied, setCopied] = useState(false);

  // Request deposit address from server on mount
  useEffect(() => {
    const client = getWebSocketClient();
    if (client.isAuthenticated()) {
      requestDepositAddress();
    }

    // Listen for deposit address response
    const handleDepositAddress = (event: CustomEvent<{ address: string }>) => {
      setDepositAddress(event.detail.address);
      setIsLoadingAddress(false);
    };

    window.addEventListener('wallet:depositAddress', handleDepositAddress as EventListener);
    return () => {
      window.removeEventListener('wallet:depositAddress', handleDepositAddress as EventListener);
    };
  }, []);

  const requestDepositAddress = useCallback(() => {
    setIsLoadingAddress(true);
    const client = getWebSocketClient();
    client.sendPacket(ClientOpcode.DEPOSIT_ADDRESS_REQUEST);
  }, []);

  const copyToClipboard = useCallback(async () => {
    if (!depositAddress) return;
    try {
      await navigator.clipboard.writeText(depositAddress);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  }, [depositAddress]);

  const handleWithdraw = useCallback(async () => {
    const withdrawAmount = BigInt(amount.replace(/,/g, ''));
    if (withdrawAmount <= 0) {
      setStatus({ type: 'error', message: 'Please enter a valid amount' });
      return;
    }

    const totalGP = (player?.gpInInventory || 0) + (player?.gpInBank || 0);
    if (withdrawAmount > totalGP) {
      setStatus({ type: 'error', message: 'Insufficient GP balance' });
      return;
    }

    if (!connected || !publicKey) {
      setStatus({ type: 'error', message: 'Please connect a wallet to receive tokens' });
      return;
    }

    try {
      setStatus({ type: 'pending', message: 'Submitting withdrawal...' });

      const client = getWebSocketClient();
      const textEncoder = new TextEncoder();
      const addressBytes = textEncoder.encode(publicKey.toBase58());

      // Send withdrawal request with destination address
      const payload = new Uint8Array(8 + 1 + addressBytes.length);
      const view = new DataView(payload.buffer);
      view.setBigInt64(0, withdrawAmount, false);
      payload[8] = addressBytes.length;
      payload.set(addressBytes, 9);

      client.sendPacket(ClientOpcode.WITHDRAW_REQUEST, payload);

      const txId = `wd_${Date.now()}`;
      addPendingTransaction({
        id: txId,
        type: 'withdrawal',
        amount: Number(withdrawAmount),
        status: 'pending',
        timestamp: Date.now(),
      });

      setStatus({
        type: 'success',
        message: 'Withdrawal request submitted!',
      });
      setAmount('');
      setMode(null);
    } catch (error) {
      console.error('Withdrawal failed:', error);
      setStatus({
        type: 'error',
        message: error instanceof Error ? error.message : 'Withdrawal failed',
      });
    }
  }, [amount, player, connected, publicKey, addPendingTransaction]);

  const formatNumber = (num: number): string => {
    return num.toLocaleString();
  };

  const quickAmounts = [100000, 1000000, 10000000];

  return (
    <div className="wallet-panel">
      <h3>Deposit & Withdraw</h3>

      {/* GP Balance */}
      <div className="gp-info">
        <div className="gp-label">In-game GP:</div>
        <div className="gp-amount">{formatNumber((player?.gpInInventory || 0) + (player?.gpInBank || 0))}</div>
      </div>

      {/* Transaction Mode Buttons */}
      <div className="transaction-buttons">
        <button
          onClick={() => setMode(mode === 'deposit' ? null : 'deposit')}
          className={mode === 'deposit' ? 'active' : ''}
        >
          Deposit
        </button>
        <button
          onClick={() => setMode(mode === 'withdraw' ? null : 'withdraw')}
          className={mode === 'withdraw' ? 'active' : ''}
        >
          Withdraw
        </button>
      </div>

      {/* Deposit Mode - Show Deposit Address */}
      {mode === 'deposit' && (
        <div className="deposit-section">
          <p className="deposit-instructions">
            Send CSGP tokens to your personal deposit address:
          </p>

          {isLoadingAddress ? (
            <div className="loading">Loading deposit address...</div>
          ) : depositAddress ? (
            <div className="deposit-address-container">
              <div className="deposit-address">
                <code>{depositAddress}</code>
              </div>
              <button className="copy-btn" onClick={copyToClipboard}>
                {copied ? 'Copied!' : 'Copy'}
              </button>
            </div>
          ) : (
            <button onClick={requestDepositAddress} className="get-address-btn">
              Get Deposit Address
            </button>
          )}

          <div className="deposit-info">
            <p>Deposits are credited automatically after 2 confirmations.</p>
            <p>1 CSGP = 1 GP (no fees)</p>
          </div>
        </div>
      )}

      {/* Withdraw Mode - Need wallet connection */}
      {mode === 'withdraw' && (
        <div className="withdraw-section">
          <p className="withdraw-instructions">
            Connect a Solana wallet to receive your CSGP tokens:
          </p>

          <div className="wallet-connect-section">
            <WalletMultiButton />
          </div>

          {connected && publicKey && (
            <>
              <div className="wallet-info">
                <div className="wallet-label">Receiving wallet:</div>
                <div className="wallet-address">
                  {publicKey.toBase58().slice(0, 8)}...{publicKey.toBase58().slice(-8)}
                </div>
              </div>

              <div className="transaction-form">
                <div className="amount-input">
                  <input
                    type="text"
                    placeholder="Amount (GP)"
                    value={amount}
                    onChange={(e) => setAmount(e.target.value.replace(/[^0-9]/g, ''))}
                  />
                  <button onClick={handleWithdraw}>Withdraw</button>
                </div>
                <div className="quick-amounts">
                  {quickAmounts.map((amt) => (
                    <button key={amt} onClick={() => setAmount(String(amt))}>
                      {amt >= 1000000 ? `${amt / 1000000}M` : `${amt / 1000}K`}
                    </button>
                  ))}
                  <button
                    onClick={() =>
                      setAmount(String((player?.gpInInventory || 0) + (player?.gpInBank || 0)))
                    }
                  >
                    Max
                  </button>
                </div>
              </div>

              <div className="withdraw-info">
                <p>Max 5 withdrawals per hour, 20 per day</p>
                <p>1 GP = 1 CSGP (no fees)</p>
              </div>
            </>
          )}
        </div>
      )}

      {/* Status Messages */}
      {status && (
        <div className={`status-message ${status.type}`}>
          {status.message}
        </div>
      )}

      {/* Pending Transactions */}
      {wallet.pendingTransactions.length > 0 && (
        <div className="pending-transactions">
          <h4>Recent Transactions</h4>
          {wallet.pendingTransactions.map((tx) => (
            <div key={tx.id} className={`transaction-item ${tx.status}`}>
              <span>{tx.type === 'deposit' ? 'Deposit' : 'Withdraw'}</span>
              <span>{formatNumber(tx.amount)} GP</span>
              <span className="tx-status">{tx.status}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
