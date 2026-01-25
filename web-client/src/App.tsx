import React, { useEffect, useState } from 'react';
import { WalletProvider } from './wallet/WalletProvider';
import { GameCanvas } from './ui/components/GameCanvas';
import { LoginScreen } from './ui/components/LoginScreen';
import { WalletPanel } from './wallet/TransactionUI';
import { useGameStore } from './store/gameStore';

export const App: React.FC = () => {
  const { isLoggedIn, isConnected } = useGameStore();
  const [showWalletPanel, setShowWalletPanel] = useState(false);

  useEffect(() => {
    // Initialize game systems
    console.log('ChainScape initializing...');
  }, []);

  return (
    <WalletProvider>
      <div className="app-container">
        {!isLoggedIn ? (
          <LoginScreen />
        ) : (
          <>
            <GameCanvas />
            <div className="ui-overlay">
              <button
                className="wallet-toggle"
                onClick={() => setShowWalletPanel(!showWalletPanel)}
              >
                {showWalletPanel ? 'Hide Wallet' : 'Show Wallet'}
              </button>
              {showWalletPanel && <WalletPanel />}
            </div>
          </>
        )}
        {!isConnected && isLoggedIn && (
          <div className="connection-status">Connecting to server...</div>
        )}
      </div>
    </WalletProvider>
  );
};
