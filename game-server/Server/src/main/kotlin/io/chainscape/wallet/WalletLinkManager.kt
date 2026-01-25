package io.chainscape.wallet

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Manages wallet linking between game accounts and Solana wallets.
 * Handles challenge generation, signature verification, and wallet storage.
 */
class WalletLinkManager(
    private val database: WalletDatabase
) {
    private val logger = LoggerFactory.getLogger(WalletLinkManager::class.java)
    private val pendingChallenges = ConcurrentHashMap<UUID, WalletChallenge>()
    private val secureRandom = SecureRandom()

    // Challenge expiration time
    private val challengeExpirationMinutes = 5L

    companion object {
        // Solana address length
        const val SOLANA_ADDRESS_LENGTH = 44

        // Base58 alphabet for validation
        private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toSet()
    }

    /**
     * Generates a challenge for wallet verification.
     */
    fun generateChallenge(playerUUID: UUID): String {
        // Generate random nonce
        val nonceBytes = ByteArray(32)
        secureRandom.nextBytes(nonceBytes)
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }

        val challenge = WalletChallenge(
            playerUUID = playerUUID,
            nonce = nonce,
            message = buildChallengeMessage(nonce),
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(challengeExpirationMinutes)
        )

        // Store pending challenge
        pendingChallenges[playerUUID] = challenge

        logger.debug("Generated wallet challenge for player $playerUUID")
        return challenge.message
    }

    /**
     * Builds the challenge message to be signed by the wallet.
     */
    private fun buildChallengeMessage(nonce: String): String {
        return """
            ChainScape Wallet Verification

            Sign this message to link your Solana wallet to your ChainScape account.

            Nonce: $nonce
            Timestamp: ${System.currentTimeMillis()}

            This signature request will expire in $challengeExpirationMinutes minutes.
        """.trimIndent()
    }

    /**
     * Verifies a wallet signature and links the wallet to the player account.
     */
    suspend fun verifyAndLinkWallet(
        playerUUID: UUID,
        solanaAddress: String,
        signatureBase58: String
    ): WalletLinkResult {
        // Validate address format
        if (!isValidSolanaAddress(solanaAddress)) {
            return WalletLinkResult.InvalidAddress
        }

        // Get pending challenge
        val challenge = pendingChallenges[playerUUID]
            ?: return WalletLinkResult.NoChallengeFound

        // Check expiration
        if (System.currentTimeMillis() > challenge.expiresAt) {
            pendingChallenges.remove(playerUUID)
            return WalletLinkResult.ChallengeExpired
        }

        // Verify signature
        val isValid = verifyEd25519Signature(
            message = challenge.message.toByteArray(),
            signatureBase58 = signatureBase58,
            publicKeyBase58 = solanaAddress
        )

        if (!isValid) {
            return WalletLinkResult.InvalidSignature
        }

        // Check if address is already linked to another account
        val existingLink = database.getWalletByAddress(solanaAddress)
        if (existingLink != null && existingLink.playerUUID != playerUUID) {
            return WalletLinkResult.AddressAlreadyLinked
        }

        // Store the wallet link
        try {
            database.linkWallet(
                playerUUID = playerUUID,
                solanaAddress = solanaAddress,
                verificationSig = signatureBase58
            )

            pendingChallenges.remove(playerUUID)

            logger.info("Successfully linked wallet $solanaAddress to player $playerUUID")
            return WalletLinkResult.Success(solanaAddress)
        } catch (e: Exception) {
            logger.error("Failed to store wallet link", e)
            return WalletLinkResult.DatabaseError
        }
    }

    /**
     * Validates a Solana address format.
     */
    private fun isValidSolanaAddress(address: String): Boolean {
        if (address.length != SOLANA_ADDRESS_LENGTH) {
            return false
        }
        return address.all { it in BASE58_ALPHABET }
    }

    /**
     * Verifies an Ed25519 signature (Solana's signing algorithm).
     */
    private fun verifyEd25519Signature(
        message: ByteArray,
        signatureBase58: String,
        publicKeyBase58: String
    ): Boolean {
        return try {
            val signature = base58Decode(signatureBase58)
            val publicKeyBytes = base58Decode(publicKeyBase58)

            if (signature.size != 64 || publicKeyBytes.size != 32) {
                return false
            }

            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            logger.error("Signature verification failed", e)
            false
        }
    }

    /**
     * Decodes a Base58 string.
     */
    private fun base58Decode(input: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val base = alphabet.length

        var result = ByteArray(input.length)
        var resultLen = 1

        for (c in input) {
            val digit = alphabet.indexOf(c)
            if (digit < 0) throw IllegalArgumentException("Invalid Base58 character: $c")

            var carry = digit
            for (j in 0 until resultLen) {
                carry += 58 * (result[j].toInt() and 0xFF)
                result[j] = (carry and 0xFF).toByte()
                carry = carry shr 8
            }

            while (carry > 0) {
                result[resultLen++] = (carry and 0xFF).toByte()
                carry = carry shr 8
            }
        }

        // Handle leading zeros
        var leadingZeros = 0
        for (c in input) {
            if (c == '1') leadingZeros++ else break
        }

        val output = ByteArray(leadingZeros + resultLen)
        for (i in 0 until resultLen) {
            output[leadingZeros + i] = result[resultLen - 1 - i]
        }

        return output
    }

    /**
     * Gets the linked wallet for a player.
     */
    suspend fun getLinkedWallet(playerUUID: UUID): PlayerWallet? {
        return database.getWalletByPlayer(playerUUID)
    }

    /**
     * Unlinks a wallet from a player account.
     */
    suspend fun unlinkWallet(playerUUID: UUID): Boolean {
        return try {
            database.unlinkWallet(playerUUID)
            logger.info("Unlinked wallet from player $playerUUID")
            true
        } catch (e: Exception) {
            logger.error("Failed to unlink wallet", e)
            false
        }
    }

    /**
     * Cleans up expired challenges.
     */
    fun cleanupExpiredChallenges() {
        val now = System.currentTimeMillis()
        val expired = pendingChallenges.filter { it.value.expiresAt < now }
        expired.keys.forEach { pendingChallenges.remove(it) }

        if (expired.isNotEmpty()) {
            logger.debug("Cleaned up ${expired.size} expired wallet challenges")
        }
    }
}

/**
 * Represents a pending wallet verification challenge.
 */
data class WalletChallenge(
    val playerUUID: UUID,
    val nonce: String,
    val message: String,
    val createdAt: Long,
    val expiresAt: Long
)

/**
 * Result of a wallet linking attempt.
 */
sealed class WalletLinkResult {
    data class Success(val address: String) : WalletLinkResult()
    object InvalidAddress : WalletLinkResult()
    object InvalidSignature : WalletLinkResult()
    object ChallengeExpired : WalletLinkResult()
    object NoChallengeFound : WalletLinkResult()
    object AddressAlreadyLinked : WalletLinkResult()
    object DatabaseError : WalletLinkResult()
}

/**
 * Stored wallet link data.
 */
data class PlayerWallet(
    val id: Long,
    val playerUUID: UUID,
    val solanaAddress: String,
    val verificationSig: String,
    val linkedAt: Long,
    val isActive: Boolean
)

/**
 * Interface for wallet database operations.
 */
interface WalletDatabase {
    suspend fun linkWallet(playerUUID: UUID, solanaAddress: String, verificationSig: String)
    suspend fun unlinkWallet(playerUUID: UUID)
    suspend fun getWalletByPlayer(playerUUID: UUID): PlayerWallet?
    suspend fun getWalletByAddress(solanaAddress: String): PlayerWallet?
}
