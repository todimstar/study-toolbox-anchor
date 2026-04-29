package com.silentinstaller.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based WebSocket client for persistent connection to the server.
 *
 * Handles:
 * - Task notifications (server → device)
 * - Status reporting (device → server)
 * - Keep-alive ping/pong
 * - Automatic reconnection with exponential backoff
 */
class WebSocketClient(
    private val wsUrl: String,
    private val deviceId: String,
    private val psk: String
) {
    companion object {
        private const val TAG = "WSClient"
        private const val INITIAL_RECONNECT_DELAY = 1000L
        private const val MAX_RECONNECT_DELAY = 60_000L
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for WS
        .build()

    private val gson = Gson()
    private var onTaskReceived: ((JsonObject) -> Unit)? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectDelay = INITIAL_RECONNECT_DELAY

    fun setOnTaskReceived(callback: (JsonObject) -> Unit) {
        onTaskReceived = callback
    }

    fun connect() {
        val url = "$wsUrl?device_id=$deviceId&psk=$psk"
        val request = Request.Builder().url(url).build()

        Log.i(TAG, "Connecting to $url")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected – response code: ${response.code}")
                reconnectDelay = INITIAL_RECONNECT_DELAY
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val msg = gson.fromJson(text, JsonObject::class.java)
                    val type = msg.get("type")?.asString ?: ""
                    if (type == "new_task") {
                        onTaskReceived?.invoke(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Closed: $code $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}, response: ${response?.code}")
                scheduleReconnect()
            }
        })
    }

    fun sendTaskStatus(taskId: Int, status: String, error: String = "") {
        val msg = mapOf(
            "type" to "task_status",
            "task_id" to taskId,
            "status" to status,
            "error" to error
        )
        val json = gson.toJson(msg)
        webSocket?.send(json)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client shutdown")
        scope.cancel()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${reconnectDelay}ms...")
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            connect()
        }
    }
}
