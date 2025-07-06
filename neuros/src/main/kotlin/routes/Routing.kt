package ai.routes

import ai.sockets.handleModelConnection
import ai.sockets.modelSessions
import ai.models.ModelMessage
import ai.sockets.pendingRequests
import ai.utils.json
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun Application.configureRouting() {
    routing {
        webSocket("/models/connect") {
            println("New connection from model")
            handleModelConnection()
        }

        post("/input/vocal") {
            val multipart = call.receiveMultipart()
            var audioFile: File? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem && part.name == "audio") {
                    val tempFile = File.createTempFile("upload-", ".wav")
                    part.streamProvider().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    audioFile = tempFile
                }
                part.dispose()
            }

            if (audioFile == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing audio file")
                return@post
            }

            println("📁 Received audio file: ${audioFile!!.absolutePath}")
            println("📁 audio file length: ${audioFile!!.length()}")

            val sttModel = modelSessions["stt-model"]
            if (sttModel != null) {
                // Read bytes and encode base64
                val fileBytes = audioFile!!.readBytes()
                val base64Audio = Base64.encode(fileBytes)

                val requestId = UUID.randomUUID().toString()
                val deferredResponse = CompletableDeferred<String>()
                pendingRequests[requestId] = deferredResponse

                val request = ModelMessage(
                    type = "request",
                    modelId = "server",
                    payload = base64Audio,  // send base64 audio data here
                    requestId = requestId
                )
                sttModel.send(json.encodeToString(request))

                try {
                    val responseText = withTimeout(300_000) { // 300s timeout
                        deferredResponse.await()
                    }
                    call.respondText("STT recognized text: $responseText")
                } catch (e: Exception) {
                    pendingRequests.remove(requestId)
                    call.respondText("Timeout or error waiting for STT model", status = HttpStatusCode.GatewayTimeout)
                } finally {
                    audioFile?.delete() // clean up temp file
                }
            } else {
                call.respondText("STT model not connected", status = HttpStatusCode.ServiceUnavailable)
            }
        }

        post("/output/audio") {
            val inputText = call.receiveText()
            println("Received text for TTS: $inputText")

            val ttsModel = modelSessions["tts-model"]
            if (ttsModel != null) {
                val requestId = UUID.randomUUID().toString()
                val deferredResponse = CompletableDeferred<String>()
                pendingRequests[requestId] = deferredResponse

                val request = ModelMessage(
                    type = "request",
                    modelId = "server",
                    payload = inputText,
                    requestId = requestId
                )
                ttsModel.send(json.encodeToString(request))

                try {
                    val base64Audio = withTimeout(300_000) {
                        deferredResponse.await()
                    }

                    val pcmBytes = Base64.decode(base64Audio)

                    // WAV encoding
                    val sampleRate = 24000
                    val channels = 1
                    val bitsPerSample = 32 // ← was 16
                    val byteRate = sampleRate * channels * bitsPerSample / 8
                    val totalAudioLen = pcmBytes.size.toLong()
                    val totalDataLen = totalAudioLen + 36

                    val wavHeader = createWavFileHeader(totalAudioLen, totalDataLen, sampleRate, channels, byteRate)

                    val wavBytes = ByteArray(wavHeader.size + pcmBytes.size)
                    System.arraycopy(wavHeader, 0, wavBytes, 0, wavHeader.size)
                    System.arraycopy(pcmBytes, 0, wavBytes, wavHeader.size, pcmBytes.size)

                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "speech.wav").toString()
                    )
                    val audioWav = ContentType("audio", "wav")
                    call.respondBytes(wavBytes, audioWav)

                } catch (e: Exception) {
                    pendingRequests.remove(requestId)
                    println("Error or timeout waiting for TTS response: $e")
                    call.respondText("Timeout or failure from TTS model", status = HttpStatusCode.GatewayTimeout)
                } finally {
                    // Clean up to avoid memory leaks
                    pendingRequests.remove(requestId)
                }
            } else {
                call.respondText("TTS model not connected", status = HttpStatusCode.ServiceUnavailable)
            }
        }

        post("/output/text") {
            val inputText = call.receiveText()
            println("Received input for LLM: $inputText")

            val llmModel = modelSessions["llm-model"]
            if (llmModel != null) {
                val requestId = UUID.randomUUID().toString()
                val deferredResponse = CompletableDeferred<String>()
                pendingRequests[requestId] = deferredResponse

                val request = ModelMessage(
                    type = "request",
                    modelId = "server",
                    payload = inputText,
                    requestId = requestId
                )
                llmModel.send(json.encodeToString(request))

                try {
                    val llmReply = withTimeout(300_000) {
                        deferredResponse.await()
                    }
                    call.respondText("LLM says: $llmReply")
                } catch (e: Exception) {
                    pendingRequests.remove(requestId)
                    println("Timeout or error waiting for LLM response: $e")
                    call.respondText("Timeout or failure from LLM model", status = HttpStatusCode.GatewayTimeout)
                } finally {
                    pendingRequests.remove(requestId)
                }
            } else {
                call.respondText("LLM model not connected", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        post("/speak") {
            val multipart = call.receiveMultipart()
            var audioBytes: ByteArray? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem && part.name == "audio") {
                    audioBytes = part.streamProvider().readBytes()
                }
                part.dispose()
            }

            if (audioBytes == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing audio file")
                return@post
            }

            val base64AudioInput = Base64.encode(audioBytes!!)
            println("Received audio file, base64 length: ${base64AudioInput.length}")

            val sttModel = modelSessions["stt-model"]
            val llmModel = modelSessions["llm-model"]
            val ttsModel = modelSessions["tts-model"]

            if (sttModel == null || llmModel == null || ttsModel == null) {
                call.respondText("STT, LLM, or TTS model not connected", status = HttpStatusCode.ServiceUnavailable)
                return@post
            }

            try {
                // Step 1: STT (send audio base64)
                val sttRequestId = UUID.randomUUID().toString()
                val sttDeferred = CompletableDeferred<String>()
                pendingRequests[sttRequestId] = sttDeferred

                val sttRequest = ModelMessage(
                    type = "request",
                    modelId = "server",
                    payload = base64AudioInput,
                    requestId = sttRequestId
                )
                sttModel.send(json.encodeToString(sttRequest))

                val recognizedText = withTimeout(300_000) {
                    sttDeferred.await()
                }
                println("STT recognized text: $recognizedText")

                // Step 2: LLM
                val llmRequestId = UUID.randomUUID().toString()
                val llmDeferred = CompletableDeferred<String>()
                pendingRequests[llmRequestId] = llmDeferred

                val llmRequest = ModelMessage(
                    type = "request",
                    modelId = "server",
                    payload = "<BEGINNING_OF_USER> User: $recognizedText <END_OF_USER>",
                    requestId = llmRequestId
                )
                llmModel.send(json.encodeToString(llmRequest))

                val llmReply = withTimeout(300_000) {
                    llmDeferred.await()
                }
                println("LLM replied: $llmReply")

                val cleanedReply = llmReply
                    .replace("<BEGINNING_OF_SILVERWOLF>", "")
                    .replace("<END_OF_SILVERWOLF>", "")
                    .replace(Regex("""(?i)^\s*silverwolf:\s*"""), "")
                    .trim()

                // Step 3: TTS
                val ttsRequestId = UUID.randomUUID().toString()
                val ttsDeferred = CompletableDeferred<String>()
                pendingRequests[ttsRequestId] = ttsDeferred

                val ttsRequest = ModelMessage(
                    type = "request",
                    modelId = "server",
                    payload = cleanedReply,
                    requestId = ttsRequestId
                )
                ttsModel.send(json.encodeToString(ttsRequest))

                val base64AudioResponse = withTimeout(600_000) {
                    ttsDeferred.await()
                }

                val pcmBytes = Base64.decode(base64AudioResponse)

                // WAV encoding params
                val sampleRate = 24000
                val channels = 1
                val bitsPerSample = 32
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val totalAudioLen = pcmBytes.size.toLong()
                val totalDataLen = totalAudioLen + 36

                val wavHeader = createWavFileHeader(totalAudioLen, totalDataLen, sampleRate, channels, byteRate)

                val wavBytes = ByteArray(wavHeader.size + pcmBytes.size)
                System.arraycopy(wavHeader, 0, wavBytes, 0, wavHeader.size)
                System.arraycopy(pcmBytes, 0, wavBytes, wavHeader.size, pcmBytes.size)

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "response.wav").toString()
                )
                val audioWav = ContentType("audio", "wav")
                call.respondBytes(wavBytes, audioWav)

            } catch (e: Exception) {
                println("Error during /speak chain: ${e.message}")
                call.respondText("Something went wrong during STT, LLM, or TTS processing", status = HttpStatusCode.GatewayTimeout)
            } finally {
                pendingRequests.clear()
            }
        }
        get("/") {
            call.respondText("Welcome to the AI server! Use /models/connect to connect models.", ContentType.Text.Plain)
        }
    }
}

fun createWavFileHeader(
    totalAudioLen: Long,
    totalDataLen: Long,
    sampleRate: Int,
    channels: Int,
    byteRate: Int
): ByteArray {
    val header = ByteArray(44)

    fun writeIntLE(value: Int, offset: Int) {
        header[offset] = (value and 0xff).toByte()
        header[offset + 1] = (value shr 8 and 0xff).toByte()
        header[offset + 2] = (value shr 16 and 0xff).toByte()
        header[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    fun writeShortLE(value: Short, offset: Int) {
        header[offset] = (value.toInt() and 0xff).toByte()
        header[offset + 1] = (value.toInt() shr 8 and 0xff).toByte()
    }

    // RIFF header
    header[0] = 'R'.code.toByte()
    header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte()
    header[3] = 'F'.code.toByte()

    writeIntLE(totalDataLen.toInt(), 4) // File size - 8 bytes

    header[8] = 'W'.code.toByte()
    header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte()
    header[11] = 'E'.code.toByte()

    // fmt chunk
    header[12] = 'f'.code.toByte()
    header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte()
    header[15] = ' '.code.toByte()

    writeIntLE(16, 16) // Subchunk1Size
    writeShortLE(3, 20) // AudioFormat = 3 (IEEE float)
    writeShortLE(channels.toShort(), 22)
    writeIntLE(sampleRate, 24)
    writeIntLE(byteRate, 28)
    writeShortLE((channels * 4).toShort(), 32) // BlockAlign = channels * 4 bytes per sample
    writeShortLE(32, 34) // BitsPerSample = 32

    // data chunk
    header[36] = 'd'.code.toByte()
    header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte()
    header[39] = 'a'.code.toByte()

    writeIntLE(totalAudioLen.toInt(), 40) // Subchunk2Size (data size)

    return header
}
