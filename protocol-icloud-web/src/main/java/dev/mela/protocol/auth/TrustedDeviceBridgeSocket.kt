package dev.mela.protocol.auth

import dev.mela.protocol.network.AppleClientProfile
import dev.mela.protocol.network.AppleEndpointPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal interface BridgeSocket {
    suspend fun readMessage(): ByteArray

    fun send(payload: ByteArray)

    fun close()
}

internal fun interface BridgeSocketFactory {
    suspend fun open(url: String, origin: String): BridgeSocket
}

internal class OkHttpBridgeSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
) : BridgeSocketFactory {
    override suspend fun open(url: String, origin: String): BridgeSocket {
        require(url.startsWith("wss://")) { "Apple bridge URLs must use WSS" }
        AppleEndpointPolicy.requireAllowed("https://${url.removePrefix("wss://")}")
        val listener = BridgeWebSocketListener()
        val request = Request.Builder()
            .url(url)
            .header("Origin", origin)
            .header("User-Agent", AppleClientProfile.USER_AGENT)
            .build()
        val socket = client.newWebSocket(request, listener)
        listener.attach(socket)
        return try {
            listener.awaitOpen()
            listener
        } catch (error: Throwable) {
            socket.cancel()
            throw error
        }
    }

    private class BridgeWebSocketListener : WebSocketListener(), BridgeSocket {
        private val opened = CompletableDeferred<Unit>()
        private val messages = Channel<ByteArray>(Channel.UNLIMITED)
        private lateinit var socket: WebSocket

        fun attach(socket: WebSocket) {
            this.socket = socket
        }

        suspend fun awaitOpen() = opened.await()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            messages.trySend(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.trySend(text.toByteArray(Charsets.UTF_8))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.completeExceptionally(t)) messages.close(t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            messages.close()
        }

        override suspend fun readMessage(): ByteArray = messages.receive()

        override fun send(payload: ByteArray) {
            check(socket.send(ByteString.of(*payload))) { "Apple bridge WebSocket is closed" }
        }

        override fun close() {
            messages.close()
            if (!socket.close(NORMAL_CLOSURE, "")) socket.cancel()
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1_000
    }
}
