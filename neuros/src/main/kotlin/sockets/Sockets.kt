package ai.sockets

import ai.models.ModelMessage
import ai.utils.json
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.application.*

val modelSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

fun Application.configureSockets() {
    install(WebSockets)
}

suspend fun DefaultWebSocketServerSession.handleModelConnection() {
    var modelId: String? = null
    try {
        incoming.consumeEach { frame ->
            if (frame is Frame.Text) {
                val text = frame.readText()
                val msg = json.decodeFromString<ModelMessage>(text)

                when (msg.type) {
                    "register" -> {
                        modelId = msg.modelId
                        if (modelId != null) {
                            modelSessions[modelId] = this
                            println("Model registered with ID: $modelId")
                        }
                    }
                    "response" -> {
                        println("Received response from model $modelId")

                        val reqId = msg.requestId
                        if (reqId != null) {
                            pendingRequests.remove(reqId)?.complete(msg.payload ?: "")
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Error in WebSocket session for model $modelId: $e")
    } finally {
        modelId?.let {
            modelSessions.remove(it)
            println("Model $it disconnected")
        }
    }
}
