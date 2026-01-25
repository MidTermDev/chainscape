import React, { useState, useEffect, useCallback } from 'react';
import { useWallet } from '@solana/wallet-adapter-react';
import { WalletMultiButton } from '@solana/wallet-adapter-react-ui';
import { useGameStore } from '../store/gameStore';
import { getSolanaWalletService } from './WalletAdapter';
import { getWebSocketClient } from '../network/WebSocketClient';
import { PacketHandler, ClientOpcode } from '../network/PacketHandler';

type TransactionMode = 'deposit' | 'withdraw' | null;

export const WalletPanel: React.FC = () => {
  const { publicKey, signMessage, signTransaction, connected } = useWallet();
  const { wallet, player, addPendingTransaction, updateTransaction } = useGameStore();
  const [mode, setMode] = useState<TransactionMode>(null);
  const [amount, setAmount] = useState('');
  const [status, setStatus] = useState<{ type: 'success' | 'error' | 'pending'; message: string } | null>(null);
  const [isLinking, setIsLinking] = useState(false);
  const walletService = getSolanaWalletService();

  // Listen for wallet link challenges from server
  useEffect(() => {
    const handleChallenge = async (event: CustomEvent<{ challenge: string }>) => {
      if (!signMessage || !publicKey) return;

      try {
        const message = walletService.generateChallengeMessage(event.detail.challenge);
        const signature = await signMessage(message);
        const encodedSig = walletService.encodeSignature(signature);

        // Send verification to server
        const client = getWebSocketClient();
        const textEncoder = new TextEncoder();
        const addressBytes = textEncoder.encode(publicKey.toBase58());
        const sigBytes = textEncoder.encode(encodedSig);
        const payload = new Uint8Array(2 + addressBytes.length + sigBytes.length);

        payload[0] = addressBytes.length;
        payload.set(addressBytes, 1);
        payload[1 + addressBytes.length] = sigBytes.length;
        payload.set(sigBytes, 2 + addressBytes.length);

        client.sendPacket(ClientOpcode.WALLET_VERIFY, payload);
      } catch (error) {
        console.error('Failed to sign challenge:', error);
        setStatus({ type: 'error', message: 'Failed to sign verification message' });
        setIsLinking(false);
      }
    };

    const handleLinkResult = (event: CustomEvent<{ success: boolean }>) => {
      setIsLinking(false);
      if (event.detail.success) {
        setStatus({ type: 'success', message: 'Wallet linked successfully!' });
      } else {
        setStatus({ type: 'error', message: 'Failed to link wallet' });
      }
    };

    window.addEventListener('wallet:challenge', handleChallenge as EventListener);
    window.addEventListener('wallet:linkResult', handleLinkResult as EventListener);

    return () => {
      window.removeEventListener('wallet:challenge', handleChallenge as EventListener);
      window.removeEventListener('wallet:linkResult', handleLinkResult as EventListener);
    };
  }, [signMessage, publicKey, walletService]);

  const handleLinkWallet = useCallback(async () => {
    if (!connected || !publicKey) {
      setStatus({ type: 'error', message: 'Please connect your wallet first' });
      return;
    }

    setIsLinking(true);
    setStatus({ type: 'pending', message: 'Requesting verification challenge...' });

    // Request challenge from server
    const client = getWebSocketClient();
    client.sendPacket(ClientOpcode.WALLET_LINK_REQUEST);
  }, [connected, publicKey]);

  const handleDeposit = useCallback(async () => {
    const depositAmount = BigInt(amount.replace(/,/g, ''));
    if (depositAmount <= 0) {
      setStatus({ type: 'error', message: 'Please enter a valid amount' });
      return;
    }

    if (!publicKey || !signTransaction) {
      setStatus({ type: 'error', message: 'Wallet not connected' });
      return;
    }

    if (depositAmount > wallet.balance) {
      setStatus({ type: 'error', message: 'Insufficient CSGP balance' });
      return;
    }

    try {
      setStatus({ type: 'pending', message: 'Creating transaction...' });

      const transaction = await walletService.createBurnTransaction(
        publicKey,
        depositAmount
      );

      const signedTx = await signTransaction(transaction);
      setStatus({ type: 'pending', message: 'Submitting transaction...' });

      const signature = await walletService.sendAndConfirmTransaction(signedTx);

      const txId = `dep_${Date.now()}`;
      addPendingTransaction({
        id: txId,
        type: 'deposit',
        amount: Number(depositAmount),
        status: 'pending',
        signature,
        timestamp: Date.now(),
      });

      setStatus({
        type: 'success',
        message: `Deposit submitted! TX: ${signature.slice(0, 8)}...`,
      });
      setAmount('');
      setMode(null);
    } catch (error) {
      console.error('Deposit failed:', error);
      setStatus({
        type: 'error',
        message: error instanceof Error ? error.message : 'Deposit failed',
      });
    }
  }, [amount, publicKey, signTransaction, wallet.balance, walletService, addPendingTransaction]);

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

    if (!wallet.isLinked) {
      setStatus({ type: 'error', message: 'Please link your wallet first' });
      return;
    }

    try {
      setStatus({ type: 'pending', message: 'Submitting withdrawal...' });

      const client = getWebSocketClient();
      const payload = new Uint8Array(8);
      const view = new DataView(payload.buffer);
      view.setBigInt64(0, withdrawAmount, false);
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
  }, [amount, player, wallet.isLinked, addPendingTransaction]);

  const formatNumber = (num: number): string => {
    return num.toLocaleString();
  };

  const quickAmounts = [100000, 1000000, 10000000];

  return (
    <div className="wallet-panel">
      <h3>Wallet</h3>

      <div className="wallet-connect-section">
        <WalletMultiButton />
      </div>

      {connected && publicKey && (
        <>
          <div className="wallet-info">
            <div className="wallet-address">
              {publicKey.toBase58().slice(0, 8)}...{publicKey.toBase58().slice(-8)}
            </div>
            <div className="wallet-balance">
              {formatNumber(wallet.balance)} CSGP
            </div>
          </div>

          {!wallet.isLinked && (
            <button
              className="link-wallet-btn"
              onClick={handleLinkWallet}
              disabled={isLinking}
            >
              {isLinking ? 'Linking...' : 'Link Wallet to Account'}
            </button>
          )}

          {wallet.isLinked && (
            <>
              <div className="gp-info">
                <div>In-game GP: {formatNumber((player?.gpInInventory || 0) + (player?.gpInBank || 0))}</div>
              </div>

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

              {mode && (
                <div className="transaction-form">
                  <div className="amount-input">
                    <input
                      type="text"
                      placeholder="Amount"
                      value={amount}
                      onChange={(e) => setAmount(e.target.value.replace(/[^0-9]/g, ''))}
                    />
                    <button onClick={mode === 'deposit' ? handleDeposit : handleWithdraw}>
                      {mode === 'deposit' ? 'Deposit' : 'Withdraw'}
                    </button>
                  </div>
                  <div className="quick-amounts">
                    {quickAmounts.map((amt) => (
                      <button key={amt} onClick={() => setAmount(String(amt))}>
                        {amt >= 1000000 ? `${amt / 1000000}M` : `${amt / 1000}K`}
                      </button>
                    ))}
                    <button
                      onClick={() =>
                        setAmount(
                          String(
                            mode === 'deposit'
                              ? wallet.balance
                              : (player?.gpInInventory || 0) + (player?.gpInBank || 0)
                          )
                        )
                      }
                    >
                      Max
                    </button>
                  </div>
                </div>
              )}
            </>
          )}

          {status && (
            <div className={`status-message ${status.type}`}>
              {status.message}
            </div>
          )}

          {wallet.pendingTransactions.length > 0 && (
            <div className="pending-transactions">
              <h4>Pending Transactions</h4>
              {wallet.pendingTransactions.map((tx) => (
                <div key={tx.id} className={`transaction-item ${tx.status}`}>
                  <span>{tx.type === 'deposit' ? 'Deposit' : 'Withdraw'}</span>
                  <span>{formatNumber(tx.amount)} GP</span>
                  <span className="tx-status">{tx.status}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
};
