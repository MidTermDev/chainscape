# ChainScape

**OSRS Private Server with Solana GP Token Integration**

ChainScape is a browser-based RuneScape private server (2009 era, pre-EoC with HD graphics) where GP (gold pieces) is backed 1:1 by a Solana SPL token (CSGP). Players can deposit tokens (burned on-chain -> credited in-game) and withdraw GP (minted on-chain -> deducted in-game).

## Features

- **Browser-based Play** - No client download required, reducing friction for crypto users
- **Full Vanilla Experience** - All skills, quests, Grand Exchange, minigames
- **2009-era Content** - HD graphics, pre-Evolution of Combat
- **Solana Integration** - 1 CSGP token = 1 GP, instant deposits and withdrawals
- **Wallet Connect** - Native Phantom/Solflare integration

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     BROWSER (No Download)                        │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ WebGL Renderer  │  │ Game Logic      │  │ Phantom/Solflare│ │
│  │ (ThreeJS)       │  │ (TypeScript)    │  │ Wallet Connect  │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘ │
│           └────────────────────┼────────────────────┘           │
└────────────────────────────────┼─────────────────────────────────┘
                                 │ WebSocket
              ┌──────────────────┴──────────────────┐
              │         Game Server (Java/Kotlin)   │
              │         Blockchain Bridge Service   │
              └──────────────────┬──────────────────┘
                                 │
              ┌──────────────────┴──────────────────┐
              │         Solana Blockchain           │
              │  CSGP Token (SPL) + Multisig        │
              └─────────────────────────────────────┘
```

## Project Structure

```
chainscape/
├── web-client/           # Browser client (TypeScript/React)
├── game-server/          # 2009scape fork with blockchain extensions
├── blockchain-bridge/    # Solana bridge service (Java)
├── common/               # Shared DTOs and events
├── database/migrations/  # PostgreSQL schema
└── infrastructure/       # Docker, K8s configs
```

## Quick Start

### Prerequisites

- Node.js 20+
- Java 17+
- Docker & Docker Compose
- PostgreSQL 15+
- Redis 7+

### Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/your-org/chainscape.git
   cd chainscape
   ```

2. Copy environment configuration:
   ```bash
   cp .env.example .env
   # Edit .env with your configuration
   ```

3. Start infrastructure services:
   ```bash
   docker-compose -f infrastructure/docker/docker-compose.yml up -d postgres redis
   ```

4. Run database migrations:
   ```bash
   psql -h localhost -U chainscape -d chainscape -f database/migrations/V001__initial_schema.sql
   ```

5. Start the game server:
   ```bash
   cd game-server
   ./gradlew run
   ```

6. Start the web client:
   ```bash
   cd web-client
   npm install
   npm run dev
   ```

7. Open http://localhost:3000 in your browser

### Production Deployment

```bash
# Build and deploy all services
docker-compose -f infrastructure/docker/docker-compose.yml up -d --build
```

## Token Configuration

### CSGP Token Properties

| Property | Value |
|----------|-------|
| Name | ChainScape Gold |
| Symbol | CSGP |
| Decimals | 6 |
| Initial Supply | 1,000,000,000 (1 billion) |
| Mint Authority | Squads Multisig |

> **Note**: 1 CSGP = 1 GP. With 6 decimals, on-chain amounts are multiplied by 10^6.

### Creating the Token (Devnet)

```bash
# Install Solana CLI
sh -c "$(curl -sSfL https://release.solana.com/stable/install)"

# Create token with 6 decimals
spl-token create-token --decimals 6

# Create token account
spl-token create-account <TOKEN_MINT_ADDRESS>

# Mint initial supply (1 billion tokens = 1_000_000_000_000_000 smallest units)
spl-token mint <TOKEN_MINT_ADDRESS> 1000000000000000
```

## Core Flows

### Deposit (Blockchain -> Game)
1. Player burns CSGP tokens (via wallet UI)
2. Bridge service detects burn transaction
3. Verifies 32+ confirmations
4. Credits equivalent GP to player's account
5. Logs transaction for audit

### Withdrawal (Game -> Blockchain)
1. Player uses `::withdraw <amount>` in-game
2. Server validates balance, rate limits, fraud score
3. Atomically debits GP
4. Bridge queues mint transaction
5. Tokens arrive in player's wallet

## Security Features

- **Multisig Mint Authority** - 3-of-5 required for minting
- **Atomic Operations** - DB transactions with row-level locking
- **Idempotency** - Transaction signatures prevent double-processing
- **Rate Limiting** - 5 withdrawals/hour, 20/day
- **Fraud Detection** - Risk scoring based on patterns
- **Reconciliation** - Hourly GP vs token supply verification
- **Circuit Breakers** - Auto-pause on anomalies

## API Reference

### In-Game Commands

| Command | Description |
|---------|-------------|
| `::linkwallet` | Start wallet linking process |
| `::verifywallet <address> <signature>` | Complete wallet verification |
| `::unlinkwallet` | Unlink current wallet |
| `::withdraw <amount>` | Withdraw GP to wallet |
| `::walletinfo` | Show linked wallet info |
| `::gpbalance` | Show GP balance breakdown |

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `./gradlew test` and `npm test`
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Disclaimer

This is an educational project. RuneScape is a trademark of Jagex Ltd. This project is not affiliated with or endorsed by Jagex.
