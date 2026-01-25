package io.chainscape.wallet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * In-game commands for wallet operations.
 * These commands allow players to link wallets and withdraw GP.
 */
class WalletCommands(
    private val walletManager: WalletLinkManager,
    private val withdrawalService: WithdrawalService
) {
    private val logger = LoggerFactory.getLogger(WalletCommands::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Handler for ::linkwallet command.
     * Initiates the wallet linking process by generating a challenge.
     */
    fun handleLinkWalletCommand(playerUUID: UUID, playerName: String, sendMessage: (String) -> Unit) {
        scope.launch {
            try {
                // Check if already linked
                val existingWallet = walletManager.getLinkedWallet(playerUUID)
                if (existingWallet != null) {
                    sendMessage("Your account is already linked to wallet: ${existingWallet.solanaAddress.take(8)}...${existingWallet.solanaAddress.takeLast(8)}")
                    sendMessage("Use ::unlinkwallet to unlink your current wallet first.")
                    return@launch
                }

                // Generate challenge
                val challengeMessage = walletManager.generateChallenge(playerUUID)

                sendMessage("=== Wallet Linking ===")
                sendMessage("A challenge has been generated for wallet verification.")
                sendMessage("Please sign the challenge message in your wallet.")
                sendMessage("Then use: ::verifywallet <address> <signature>")
                sendMessage("Challenge expires in 5 minutes.")

                // In a real implementation, this would be sent to the client
                // to display in a UI for wallet signing
                logger.info("Generated wallet challenge for player $playerName ($playerUUID)")

            } catch (e: Exception) {
                logger.error("Error in linkwallet command", e)
                sendMessage("An error occurred. Please try again.")
            }
        }
    }

    /**
     * Handler for ::verifywallet <address> <signature> command.
     * Verifies the wallet signature and completes the linking process.
     */
    fun handleVerifyWalletCommand(
        playerUUID: UUID,
        playerName: String,
        args: Array<String>,
        sendMessage: (String) -> Unit
    ) {
        if (args.size < 2) {
            sendMessage("Usage: ::verifywallet <solana_address> <signature>")
            return
        }

        val solanaAddress = args[0]
        val signature = args[1]

        scope.launch {
            try {
                val result = walletManager.verifyAndLinkWallet(playerUUID, solanaAddress, signature)

                when (result) {
                    is WalletLinkResult.Success -> {
                        sendMessage("Wallet linked successfully!")
                        sendMessage("Address: ${result.address}")
                        sendMessage("You can now deposit and withdraw GP.")
                    }
                    is WalletLinkResult.InvalidAddress -> {
                        sendMessage("Invalid Solana address format.")
                    }
                    is WalletLinkResult.InvalidSignature -> {
                        sendMessage("Invalid signature. Please try again.")
                    }
                    is WalletLinkResult.ChallengeExpired -> {
                        sendMessage("Challenge expired. Use ::linkwallet to generate a new one.")
                    }
                    is WalletLinkResult.NoChallengeFound -> {
                        sendMessage("No pending challenge found. Use ::linkwallet first.")
                    }
                    is WalletLinkResult.AddressAlreadyLinked -> {
                        sendMessage("This wallet is already linked to another account.")
                    }
                    is WalletLinkResult.DatabaseError -> {
                        sendMessage("An error occurred. Please try again later.")
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in verifywallet command", e)
                sendMessage("An error occurred. Please try again.")
            }
        }
    }

    /**
     * Handler for ::unlinkwallet command.
     * Unlinks the wallet from the player's account.
     */
    fun handleUnlinkWalletCommand(playerUUID: UUID, playerName: String, sendMessage: (String) -> Unit) {
        scope.launch {
            try {
                val existingWallet = walletManager.getLinkedWallet(playerUUID)
                if (existingWallet == null) {
                    sendMessage("No wallet is currently linked to your account.")
                    return@launch
                }

                val success = walletManager.unlinkWallet(playerUUID)
                if (success) {
                    sendMessage("Wallet unlinked successfully.")
                    sendMessage("You will no longer be able to deposit or withdraw GP.")
                } else {
                    sendMessage("Failed to unlink wallet. Please try again.")
                }
            } catch (e: Exception) {
                logger.error("Error in unlinkwallet command", e)
                sendMessage("An error occurred. Please try again.")
            }
        }
    }

    /**
     * Handler for ::withdraw <amount> command.
     * Initiates a GP withdrawal to the linked wallet.
     */
    fun handleWithdrawCommand(
        playerUUID: UUID,
        playerName: String,
        args: Array<String>,
        getGPBalance: () -> Long,
        deductGP: (Long) -> Boolean,
        sendMessage: (String) -> Unit
    ) {
        if (args.isEmpty()) {
            sendMessage("Usage: ::withdraw <amount>")
            sendMessage("Example: ::withdraw 1000000")
            return
        }

        val amountStr = args[0].replace(",", "").replace("k", "000").replace("m", "000000")
        val amount = amountStr.toLongOrNull()

        if (amount == null || amount <= 0) {
            sendMessage("Invalid amount. Please enter a positive number.")
            return
        }

        scope.launch {
            try {
                // Check if wallet is linked
                val wallet = walletManager.getLinkedWallet(playerUUID)
                if (wallet == null) {
                    sendMessage("No wallet linked. Use ::linkwallet first.")
                    return@launch
                }

                // Check GP balance
                val balance = getGPBalance()
                if (amount > balance) {
                    sendMessage("Insufficient GP balance. You have: ${formatGP(balance)}")
                    return@launch
                }

                // Attempt withdrawal
                val result = withdrawalService.requestWithdrawal(
                    playerUUID = playerUUID,
                    solanaAddress = wallet.solanaAddress,
                    amount = amount,
                    deductGP = deductGP
                )

                when (result) {
                    is WithdrawalResult.Success -> {
                        sendMessage("Withdrawal request submitted!")
                        sendMessage("Amount: ${formatGP(amount)} GP")
                        sendMessage("Transaction ID: ${result.transactionId}")
                        sendMessage("Tokens will arrive in your wallet shortly.")
                    }
                    is WithdrawalResult.RateLimited -> {
                        sendMessage("Withdrawal rate limit exceeded.")
                        sendMessage("Max 5 withdrawals per hour, 20 per day.")
                    }
                    is WithdrawalResult.InsufficientBalance -> {
                        sendMessage("Insufficient GP balance.")
                    }
                    is WithdrawalResult.WalletNotLinked -> {
                        sendMessage("Wallet not linked. Use ::linkwallet first.")
                    }
                    is WithdrawalResult.CircuitBreakerOpen -> {
                        sendMessage("Withdrawals are temporarily disabled. Please try again later.")
                    }
                    is WithdrawalResult.Error -> {
                        sendMessage("Withdrawal failed: ${result.message}")
                        sendMessage("Your GP has not been deducted.")
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in withdraw command", e)
                sendMessage("An error occurred. Please try again.")
            }
        }
    }

    /**
     * Handler for ::walletinfo command.
     * Shows the player's wallet information.
     */
    fun handleWalletInfoCommand(playerUUID: UUID, sendMessage: (String) -> Unit) {
        scope.launch {
            try {
                val wallet = walletManager.getLinkedWallet(playerUUID)
                if (wallet == null) {
                    sendMessage("No wallet linked to your account.")
                    sendMessage("Use ::linkwallet to link a wallet.")
                    return@launch
                }

                sendMessage("=== Wallet Info ===")
                sendMessage("Address: ${wallet.solanaAddress}")
                sendMessage("Linked: ${formatTimestamp(wallet.linkedAt)}")
                sendMessage("Status: ${if (wallet.isActive) "Active" else "Inactive"}")

            } catch (e: Exception) {
                logger.error("Error in walletinfo command", e)
                sendMessage("An error occurred. Please try again.")
            }
        }
    }

    /**
     * Handler for ::gpbalance command.
     * Shows the player's GP balance breakdown.
     */
    fun handleGPBalanceCommand(
        playerUUID: UUID,
        inventoryGP: Long,
        bankGP: Long,
        geGP: Long,
        sendMessage: (String) -> Unit
    ) {
        sendMessage("=== GP Balance ===")
        sendMessage("Inventory: ${formatGP(inventoryGP)}")
        sendMessage("Bank: ${formatGP(bankGP)}")
        sendMessage("Grand Exchange: ${formatGP(geGP)}")
        sendMessage("Total: ${formatGP(inventoryGP + bankGP + geGP)}")
    }

    private fun formatGP(amount: Long): String {
        return when {
            amount >= 1_000_000_000 -> String.format("%.2fB", amount / 1_000_000_000.0)
            amount >= 1_000_000 -> String.format("%.2fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%.2fK", amount / 1_000.0)
            else -> amount.toString()
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date)
    }
}

/**
 * Service for handling withdrawal requests.
 */
interface WithdrawalService {
    suspend fun requestWithdrawal(
        playerUUID: UUID,
        solanaAddress: String,
        amount: Long,
        deductGP: (Long) -> Boolean
    ): WithdrawalResult
}

/**
 * Result of a withdrawal request.
 */
sealed class WithdrawalResult {
    data class Success(val transactionId: String) : WithdrawalResult()
    object RateLimited : WithdrawalResult()
    object InsufficientBalance : WithdrawalResult()
    object WalletNotLinked : WithdrawalResult()
    object CircuitBreakerOpen : WithdrawalResult()
    data class Error(val message: String) : WithdrawalResult()
}
