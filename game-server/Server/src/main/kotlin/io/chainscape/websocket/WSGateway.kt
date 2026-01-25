package io.chainscape.websocket

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.IdleStateHandler
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket Gateway for browser-based game clients.
 * Translates WebSocket connections to the game server's internal protocol.
 */
class WSGateway(
    private val port: Int = 43595,
    private val gameServerHandler: GameServerHandler,
    private val sslEnabled: Boolean = false,
    private val sslCertPath: String? = null,
    private val sslKeyPath: String? = null
) {
    private val logger = LoggerFactory.getLogger(WSGateway::class.java)
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    private val sessions = ConcurrentHashMap<String, WSSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var channel: Channel? = null

    fun start() {
        try {
            val sslContext = if (sslEnabled && sslCertPath != null && sslKeyPath != null) {
                SslContextBuilder.forServer(File(sslCertPath), File(sslKeyPath)).build()
            } else null

            val bootstrap = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val pipeline = ch.pipeline()

                        sslContext?.let { pipeline.addLast(it.newHandler(ch.alloc())) }

                        pipeline.addLast(HttpServerCodec())
                        pipeline.addLast(HttpObjectAggregator(65536))
                        pipeline.addLast(IdleStateHandler(60, 30, 0, TimeUnit.SECONDS))
                        pipeline.addLast(WebSocketServerProtocolHandler("/ws", null, true, 65536))
                        pipeline.addLast(WSFrameHandler(this@WSGateway))
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

            channel = bootstrap.bind(port).sync().channel()
            logger.info("WebSocket Gateway started on port $port (SSL: $sslEnabled)")
        } catch (e: Exception) {
            logger.error("Failed to start WebSocket Gateway", e)
            throw e
        }
    }

    fun stop() {
        scope.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        channel?.close()?.sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
        logger.info("WebSocket Gateway stopped")
    }

    internal fun onConnect(ctx: ChannelHandlerContext) {
        val sessionId = generateSessionId()
        val session = WSSession(
            id = sessionId,
            channel = ctx.channel(),
            connectedAt = System.currentTimeMillis()
        )
        sessions[sessionId] = session
        ctx.channel().attr(SESSION_KEY).set(session)

        logger.info("WebSocket client connected: $sessionId")
    }

    internal fun onDisconnect(ctx: ChannelHandlerContext) {
        val session = ctx.channel().attr(SESSION_KEY).get() ?: return
        sessions.remove(session.id)

        scope.launch {
            gameServerHandler.onClientDisconnect(session)
        }

        logger.info("WebSocket client disconnected: ${session.id}")
    }

    internal fun onMessage(ctx: ChannelHandlerContext, frame: BinaryWebSocketFrame) {
        val session = ctx.channel().attr(SESSION_KEY).get() ?: return
        val buffer = frame.content()
        val bytes = ByteArray(buffer.readableBytes())
        buffer.readBytes(bytes)

        scope.launch {
            try {
                val packet = parsePacket(bytes)
                gameServerHandler.onPacketReceived(session, packet)
            } catch (e: Exception) {
                logger.error("Error processing packet from ${session.id}", e)
            }
        }
    }

    internal fun onTextMessage(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
        // For potential JSON-based protocols or debugging
        val session = ctx.channel().attr(SESSION_KEY).get() ?: return
        logger.debug("Received text message from ${session.id}: ${frame.text()}")
    }

    fun sendToSession(sessionId: String, data: ByteArray) {
        val session = sessions[sessionId] ?: return
        session.send(data)
    }

    fun sendToAll(data: ByteArray) {
        sessions.values.forEach { it.send(data) }
    }

    fun getSession(sessionId: String): WSSession? = sessions[sessionId]

    fun getSessionCount(): Int = sessions.size

    private fun parsePacket(bytes: ByteArray): GamePacket {
        if (bytes.size < 3) {
            throw IllegalArgumentException("Packet too short")
        }

        val buffer = ByteBuffer.wrap(bytes)
        val opcode = buffer.get().toInt() and 0xFF
        val length = buffer.short.toInt() and 0xFFFF

        if (bytes.size < 3 + length) {
            throw IllegalArgumentException("Packet length mismatch")
        }

        val payload = ByteArray(length)
        buffer.get(payload)

        return GamePacket(opcode, payload)
    }

    private fun generateSessionId(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }

    companion object {
        private val SESSION_KEY = io.netty.util.AttributeKey.valueOf<WSSession>("session")
    }
}

/**
 * Netty handler for WebSocket frames.
 */
class WSFrameHandler(private val gateway: WSGateway) : SimpleChannelInboundHandler<WebSocketFrame>() {
    private val logger = LoggerFactory.getLogger(WSFrameHandler::class.java)

    override fun channelActive(ctx: ChannelHandlerContext) {
        gateway.onConnect(ctx)
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        gateway.onDisconnect(ctx)
        super.channelInactive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        when (frame) {
            is BinaryWebSocketFrame -> gateway.onMessage(ctx, frame)
            is TextWebSocketFrame -> gateway.onTextMessage(ctx, frame)
            is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(frame.content().retain()))
            is CloseWebSocketFrame -> ctx.close()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("WebSocket error", cause)
        ctx.close()
    }
}

/**
 * Represents a WebSocket session.
 */
class WSSession(
    val id: String,
    private val channel: Channel,
    val connectedAt: Long
) {
    var playerUUID: java.util.UUID? = null
    var playerName: String? = null
    var isAuthenticated: Boolean = false

    fun send(data: ByteArray) {
        if (channel.isActive) {
            val buffer = channel.alloc().buffer(data.size)
            buffer.writeBytes(data)
            channel.writeAndFlush(BinaryWebSocketFrame(buffer))
        }
    }

    fun sendPacket(opcode: Int, payload: ByteArray = ByteArray(0)) {
        val packet = ByteArray(3 + payload.size)
        packet[0] = opcode.toByte()
        packet[1] = ((payload.size shr 8) and 0xFF).toByte()
        packet[2] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 3, payload.size)
        send(packet)
    }

    fun close() {
        channel.close()
    }

    fun isActive(): Boolean = channel.isActive
}

/**
 * Game packet structure.
 */
data class GamePacket(
    val opcode: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GamePacket
        if (opcode != other.opcode) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Interface for game server packet handling.
 */
interface GameServerHandler {
    suspend fun onPacketReceived(session: WSSession, packet: GamePacket)
    suspend fun onClientDisconnect(session: WSSession)
}

/**
 * Server -> Client opcodes.
 */
object ServerOpcodes {
    const val HEARTBEAT = 0x00
    const val LOGIN_RESPONSE = 0x01
    const val PLAYER_UPDATE = 0x02
    const val INVENTORY_UPDATE = 0x03
    const val CHAT_MESSAGE = 0x04
    const val SYSTEM_MESSAGE = 0x05
    const val WALLET_LINK_CHALLENGE = 0x10
    const val WALLET_LINK_RESULT = 0x11
    const val GP_UPDATE = 0x12
    const val TRANSACTION_STATUS = 0x13
    const val WORLD_UPDATE = 0x20
    const val NPC_UPDATE = 0x21
    const val GROUND_ITEMS = 0x22
    const val MAP_REGION = 0x23
}

/**
 * Client -> Server opcodes.
 */
object ClientOpcodes {
    const val HEARTBEAT = 0x00
    const val LOGIN_REQUEST = 0x01
    const val LOGOUT_REQUEST = 0x02
    const val PLAYER_ACTION = 0x03
    const val CHAT_MESSAGE = 0x04
    const val WALLET_LINK_REQUEST = 0x10
    const val WALLET_VERIFY = 0x11
    const val WITHDRAW_REQUEST = 0x12
    const val MOVEMENT = 0x20
    const val INTERFACE_ACTION = 0x21
}
