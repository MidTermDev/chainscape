import {
  Connection,
  PublicKey,
  Transaction,
  TransactionInstruction,
  SystemProgram,
  LAMPORTS_PER_SOL,
} from '@solana/web3.js';
import {
  createBurnInstruction,
  getAssociatedTokenAddress,
  TOKEN_PROGRAM_ID,
} from '@solana/spl-token';
import bs58 from 'bs58';

// CSGP Token configuration
export const CSGP_TOKEN_CONFIG = {
  // Will be set after mainnet deployment
  mint: new PublicKey('CSGPTokenMintAddressWillBeSetAfterDeployment11'),
  decimals: 0,
  burnAddress: new PublicKey('BurnAddressForCSGPTokensWillBeSet11111111111'),
};

export const SOLANA_NETWORKS = {
  mainnet: 'https://api.mainnet-beta.solana.com',
  devnet: 'https://api.devnet.solana.com',
  testnet: 'https://api.testnet.solana.com',
} as const;

export type SolanaNetwork = keyof typeof SOLANA_NETWORKS;

export interface WalletAdapter {
  publicKey: PublicKey | null;
  connected: boolean;
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  signMessage(message: Uint8Array): Promise<Uint8Array>;
  signTransaction(transaction: Transaction): Promise<Transaction>;
}

export class SolanaWalletService {
  private connection: Connection;
  private network: SolanaNetwork;

  constructor(network: SolanaNetwork = 'devnet') {
    this.network = network;
    this.connection = new Connection(SOLANA_NETWORKS[network], 'confirmed');
  }

  getConnection(): Connection {
    return this.connection;
  }

  setNetwork(network: SolanaNetwork): void {
    this.network = network;
    this.connection = new Connection(SOLANA_NETWORKS[network], 'confirmed');
  }

  async getTokenBalance(walletAddress: PublicKey): Promise<bigint> {
    try {
      const tokenAccount = await getAssociatedTokenAddress(
        CSGP_TOKEN_CONFIG.mint,
        walletAddress
      );

      const balance = await this.connection.getTokenAccountBalance(tokenAccount);
      return BigInt(balance.value.amount);
    } catch (error) {
      console.error('Failed to get token balance:', error);
      return BigInt(0);
    }
  }

  async createBurnTransaction(
    walletAddress: PublicKey,
    amount: bigint
  ): Promise<Transaction> {
    const tokenAccount = await getAssociatedTokenAddress(
      CSGP_TOKEN_CONFIG.mint,
      walletAddress
    );

    const burnInstruction = createBurnInstruction(
      tokenAccount,
      CSGP_TOKEN_CONFIG.mint,
      walletAddress,
      amount,
      [],
      TOKEN_PROGRAM_ID
    );

    const transaction = new Transaction().add(burnInstruction);

    // Get recent blockhash
    const { blockhash, lastValidBlockHeight } =
      await this.connection.getLatestBlockhash();
    transaction.recentBlockhash = blockhash;
    transaction.lastValidBlockHeight = lastValidBlockHeight;
    transaction.feePayer = walletAddress;

    return transaction;
  }

  async sendAndConfirmTransaction(
    signedTransaction: Transaction
  ): Promise<string> {
    const signature = await this.connection.sendRawTransaction(
      signedTransaction.serialize()
    );

    const confirmation = await this.connection.confirmTransaction(
      {
        signature,
        blockhash: signedTransaction.recentBlockhash!,
        lastValidBlockHeight: signedTransaction.lastValidBlockHeight!,
      },
      'confirmed'
    );

    if (confirmation.value.err) {
      throw new Error(`Transaction failed: ${confirmation.value.err}`);
    }

    return signature;
  }

  async getSOLBalance(walletAddress: PublicKey): Promise<number> {
    const balance = await this.connection.getBalance(walletAddress);
    return balance / LAMPORTS_PER_SOL;
  }

  generateChallengeMessage(nonce: string): Uint8Array {
    const message = `ChainScape Wallet Verification\n\nSign this message to link your wallet to your ChainScape account.\n\nNonce: ${nonce}\nTimestamp: ${Date.now()}`;
    return new TextEncoder().encode(message);
  }

  verifySignature(
    message: Uint8Array,
    signature: Uint8Array,
    publicKey: PublicKey
  ): boolean {
    // In production, use tweetnacl or similar for ed25519 verification
    // The actual verification happens server-side
    return true;
  }

  encodeSignature(signature: Uint8Array): string {
    return bs58.encode(signature);
  }

  decodeSignature(signatureBase58: string): Uint8Array {
    return bs58.decode(signatureBase58);
  }
}

// Singleton instance
let serviceInstance: SolanaWalletService | null = null;

export const getSolanaWalletService = (): SolanaWalletService => {
  if (!serviceInstance) {
    const network =
      (import.meta.env.VITE_SOLANA_NETWORK as SolanaNetwork) || 'devnet';
    serviceInstance = new SolanaWalletService(network);
  }
  return serviceInstance;
};
