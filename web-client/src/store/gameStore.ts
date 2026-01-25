import { create } from 'zustand';

export interface Player {
  username: string;
  rights: number;
  position: { x: number; y: number; z: number };
  stats: Record<string, number>;
  inventory: InventoryItem[];
  gpInInventory: number;
  gpInBank: number;
}

export interface InventoryItem {
  id: number;
  amount: number;
  slot: number;
}

export interface WalletState {
  address: string | null;
  balance: number;
  isLinked: boolean;
  pendingTransactions: PendingTransaction[];
}

export interface PendingTransaction {
  id: string;
  type: 'deposit' | 'withdrawal';
  amount: number;
  status: 'pending' | 'confirmed' | 'failed';
  signature?: string;
  timestamp: number;
}

interface GameState {
  // Connection state
  isConnected: boolean;
  isLoggedIn: boolean;
  sessionId: string | null;

  // Player state
  player: Player | null;

  // Wallet state
  wallet: WalletState;

  // Actions
  setConnected: (connected: boolean) => void;
  setLoggedIn: (loggedIn: boolean) => void;
  setSessionId: (sessionId: string | null) => void;
  setPlayer: (player: Player | null) => void;
  updatePlayer: (updates: Partial<Player>) => void;
  setWallet: (wallet: Partial<WalletState>) => void;
  addPendingTransaction: (tx: PendingTransaction) => void;
  updateTransaction: (id: string, updates: Partial<PendingTransaction>) => void;
  removePendingTransaction: (id: string) => void;
  reset: () => void;
}

const initialWalletState: WalletState = {
  address: null,
  balance: 0,
  isLinked: false,
  pendingTransactions: [],
};

export const useGameStore = create<GameState>((set) => ({
  // Initial state
  isConnected: false,
  isLoggedIn: false,
  sessionId: null,
  player: null,
  wallet: initialWalletState,

  // Actions
  setConnected: (connected) => set({ isConnected: connected }),

  setLoggedIn: (loggedIn) => set({ isLoggedIn: loggedIn }),

  setSessionId: (sessionId) => set({ sessionId }),

  setPlayer: (player) => set({ player }),

  updatePlayer: (updates) =>
    set((state) => ({
      player: state.player ? { ...state.player, ...updates } : null,
    })),

  setWallet: (wallet) =>
    set((state) => ({
      wallet: { ...state.wallet, ...wallet },
    })),

  addPendingTransaction: (tx) =>
    set((state) => ({
      wallet: {
        ...state.wallet,
        pendingTransactions: [...state.wallet.pendingTransactions, tx],
      },
    })),

  updateTransaction: (id, updates) =>
    set((state) => ({
      wallet: {
        ...state.wallet,
        pendingTransactions: state.wallet.pendingTransactions.map((tx) =>
          tx.id === id ? { ...tx, ...updates } : tx
        ),
      },
    })),

  removePendingTransaction: (id) =>
    set((state) => ({
      wallet: {
        ...state.wallet,
        pendingTransactions: state.wallet.pendingTransactions.filter(
          (tx) => tx.id !== id
        ),
      },
    })),

  reset: () =>
    set({
      isConnected: false,
      isLoggedIn: false,
      sessionId: null,
      player: null,
      wallet: initialWalletState,
    }),
}));
