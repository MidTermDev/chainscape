package io.chainscape.economy

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Economy Hook for tracking all GP changes in the game.
 * This hooks into the game's container system to monitor GP flow.
 */
object EconomyHook {
    private val logger = LoggerFactory.getLogger(EconomyHook::class.java)
    private val listeners = mutableListOf<GPChangeListener>()
    private val playerGPCache = ConcurrentHashMap<UUID, Long>()

    // GP Item ID in OSRS
    const val GP_ITEM_ID = 995

    // Maximum GP that can be held in one stack
    const val MAX_GP_STACK = Int.MAX_VALUE.toLong()

    interface GPChangeListener {
        fun onGPChange(event: GPChangeEvent)
    }

    fun registerListener(listener: GPChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        logger.info("Registered GP change listener: ${listener::class.simpleName}")
    }

    fun unregisterListener(listener: GPChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Called when GP changes in a player's inventory.
     */
    fun onInventoryGPChange(playerUUID: UUID, oldAmount: Long, newAmount: Long, source: GPChangeSource) {
        if (oldAmount == newAmount) return

        val event = GPChangeEvent(
            playerUUID = playerUUID,
            changeType = if (newAmount > oldAmount) GPChangeType.GAIN else GPChangeType.LOSS,
            amount = kotlin.math.abs(newAmount - oldAmount),
            source = source,
            location = GPLocation.INVENTORY,
            balanceBefore = oldAmount,
            balanceAfter = newAmount,
            timestamp = System.currentTimeMillis()
        )

        dispatchEvent(event)
    }

    /**
     * Called when GP changes in a player's bank.
     */
    fun onBankGPChange(playerUUID: UUID, oldAmount: Long, newAmount: Long, source: GPChangeSource) {
        if (oldAmount == newAmount) return

        val event = GPChangeEvent(
            playerUUID = playerUUID,
            changeType = if (newAmount > oldAmount) GPChangeType.GAIN else GPChangeType.LOSS,
            amount = kotlin.math.abs(newAmount - oldAmount),
            source = source,
            location = GPLocation.BANK,
            balanceBefore = oldAmount,
            balanceAfter = newAmount,
            timestamp = System.currentTimeMillis()
        )

        dispatchEvent(event)
    }

    /**
     * Called when GP is involved in a trade.
     */
    fun onTradeGP(
        senderUUID: UUID,
        receiverUUID: UUID,
        amount: Long,
        senderBalanceBefore: Long,
        receiverBalanceBefore: Long
    ) {
        // Event for sender
        val senderEvent = GPChangeEvent(
            playerUUID = senderUUID,
            changeType = GPChangeType.LOSS,
            amount = amount,
            source = GPChangeSource.TRADE,
            location = GPLocation.INVENTORY,
            balanceBefore = senderBalanceBefore,
            balanceAfter = senderBalanceBefore - amount,
            timestamp = System.currentTimeMillis(),
            relatedPlayerUUID = receiverUUID
        )

        // Event for receiver
        val receiverEvent = GPChangeEvent(
            playerUUID = receiverUUID,
            changeType = GPChangeType.GAIN,
            amount = amount,
            source = GPChangeSource.TRADE,
            location = GPLocation.INVENTORY,
            balanceBefore = receiverBalanceBefore,
            balanceAfter = receiverBalanceBefore + amount,
            timestamp = System.currentTimeMillis(),
            relatedPlayerUUID = senderUUID
        )

        dispatchEvent(senderEvent)
        dispatchEvent(receiverEvent)
    }

    /**
     * Called when GP is used in Grand Exchange.
     */
    fun onGETransaction(
        playerUUID: UUID,
        amount: Long,
        isBuying: Boolean,
        itemId: Int,
        itemAmount: Int,
        balanceBefore: Long
    ) {
        val event = GPChangeEvent(
            playerUUID = playerUUID,
            changeType = if (isBuying) GPChangeType.LOSS else GPChangeType.GAIN,
            amount = amount,
            source = if (isBuying) GPChangeSource.GE_PURCHASE else GPChangeSource.GE_SALE,
            location = GPLocation.GE_COFFER,
            balanceBefore = balanceBefore,
            balanceAfter = if (isBuying) balanceBefore - amount else balanceBefore + amount,
            timestamp = System.currentTimeMillis(),
            metadata = mapOf(
                "itemId" to itemId,
                "itemAmount" to itemAmount
            )
        )

        dispatchEvent(event)
    }

    /**
     * Called when a blockchain deposit is credited.
     */
    fun onBlockchainDeposit(playerUUID: UUID, amount: Long, signature: String, balanceAfter: Long) {
        val event = GPChangeEvent(
            playerUUID = playerUUID,
            changeType = GPChangeType.GAIN,
            amount = amount,
            source = GPChangeSource.BLOCKCHAIN_DEPOSIT,
            location = GPLocation.INVENTORY,
            balanceBefore = balanceAfter - amount,
            balanceAfter = balanceAfter,
            timestamp = System.currentTimeMillis(),
            metadata = mapOf("signature" to signature)
        )

        dispatchEvent(event)
        logger.info("Blockchain deposit credited: $amount GP to $playerUUID (sig: ${signature.take(16)}...)")
    }

    /**
     * Called when a blockchain withdrawal is processed.
     */
    fun onBlockchainWithdrawal(playerUUID: UUID, amount: Long, balanceBefore: Long) {
        val event = GPChangeEvent(
            playerUUID = playerUUID,
            changeType = GPChangeType.LOSS,
            amount = amount,
            source = GPChangeSource.BLOCKCHAIN_WITHDRAWAL,
            location = GPLocation.BANK,
            balanceBefore = balanceBefore,
            balanceAfter = balanceBefore - amount,
            timestamp = System.currentTimeMillis()
        )

        dispatchEvent(event)
        logger.info("Blockchain withdrawal debited: $amount GP from $playerUUID")
    }

    /**
     * Get total GP for a player across all locations.
     */
    fun getTotalGP(playerUUID: UUID, inventoryGP: Long, bankGP: Long, geGP: Long): Long {
        return inventoryGP + bankGP + geGP
    }

    /**
     * Update cached GP total for a player.
     */
    fun updateCachedTotal(playerUUID: UUID, total: Long) {
        playerGPCache[playerUUID] = total
    }

    /**
     * Get all cached GP totals for reconciliation.
     */
    fun getAllPlayerTotals(): Map<UUID, Long> {
        return playerGPCache.toMap()
    }

    /**
     * Calculate total GP in the game across all players.
     */
    fun calculateTotalGPInGame(): Long {
        return playerGPCache.values.sum()
    }

    private fun dispatchEvent(event: GPChangeEvent) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onGPChange(event)
                } catch (e: Exception) {
                    logger.error("Error dispatching GP change event to ${listener::class.simpleName}", e)
                }
            }
        }
    }
}

/**
 * Types of GP changes.
 */
enum class GPChangeType {
    GAIN,
    LOSS
}

/**
 * Sources of GP changes.
 */
enum class GPChangeSource {
    // Blockchain
    BLOCKCHAIN_DEPOSIT,
    BLOCKCHAIN_WITHDRAWAL,

    // Trading
    TRADE,
    GE_PURCHASE,
    GE_SALE,

    // NPCs
    SHOP_PURCHASE,
    SHOP_SALE,
    NPC_DROP,

    // Combat
    PVP_KILL,
    PVP_DEATH,

    // Ground items
    PICKUP,
    DROP,

    // Other
    QUEST_REWARD,
    MINIGAME_REWARD,
    ADMIN_COMMAND,
    UNKNOWN
}

/**
 * Locations where GP can exist.
 */
enum class GPLocation {
    INVENTORY,
    BANK,
    GE_COFFER,
    GROUND,
    TRADE_WINDOW
}

/**
 * Event data for GP changes.
 */
data class GPChangeEvent(
    val playerUUID: UUID,
    val changeType: GPChangeType,
    val amount: Long,
    val source: GPChangeSource,
    val location: GPLocation,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val timestamp: Long,
    val relatedPlayerUUID: UUID? = null,
    val metadata: Map<String, Any>? = null
)
