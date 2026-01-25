import React, { FC, ReactNode, useMemo, useCallback, useEffect } from 'react';
import {
  ConnectionProvider,
  WalletProvider as SolanaWalletProvider,
  useWallet,
} from '@solana/wallet-adapter-react';
import { WalletModalProvider } from '@solana/wallet-adapter-react-ui';
import { PhantomWalletAdapter } from '@solana/wallet-adapter-phantom';
import { SolflareWalletAdapter } from '@solana/wallet-adapter-solflare';
import { clusterApiUrl } from '@solana/web3.js';
import { useGameStore } from '../store/gameStore';
import { getSolanaWalletService } from './WalletAdapter';

import '@solana/wallet-adapter-react-ui/styles.css';

interface Props {
  children: ReactNode;
}

const WalletStateManager: FC<{ children: ReactNode }> = ({ children }) => {
  const { publicKey, connected } = useWallet();
  const { setWallet } = useGameStore();
  const walletService = getSolanaWalletService();

  useEffect(() => {
    if (connected && publicKey) {
      setWallet({ address: publicKey.toBase58() });

      // Fetch token balance
      walletService.getTokenBalance(publicKey).then((balance) => {
        setWallet({ balance: Number(balance) });
      });
    } else {
      setWallet({ address: null, balance: 0 });
    }
  }, [connected, publicKey, setWallet, walletService]);

  return <>{children}</>;
};

export const WalletProvider: FC<Props> = ({ children }) => {
  const network = (import.meta.env.VITE_SOLANA_NETWORK as 'devnet' | 'mainnet-beta') || 'devnet';
  const endpoint = useMemo(() => clusterApiUrl(network), [network]);

  const wallets = useMemo(
    () => [
      new PhantomWalletAdapter(),
      new SolflareWalletAdapter(),
    ],
    []
  );

  return (
    <ConnectionProvider endpoint={endpoint}>
      <SolanaWalletProvider wallets={wallets} autoConnect>
        <WalletModalProvider>
          <WalletStateManager>{children}</WalletStateManager>
        </WalletModalProvider>
      </SolanaWalletProvider>
    </ConnectionProvider>
  );
};
