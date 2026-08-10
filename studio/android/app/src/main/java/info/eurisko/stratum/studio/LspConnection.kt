package info.eurisko.stratum.studio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object LspFrameCodec {
    fun write(output: BufferedOutputStream, message: String) {
        val payload = message.toByteArray(UTF_8)
        output.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(UTF_8))
        output.write(payload)
        output.flush()
    }

    fun read(input: InputStream): String? {
        var length = -1
        var line = readLine(input) ?: return null
        while (line.isNotEmpty()) {
            if (line.startsWith("content-length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toInt()
            }
            line = readLine(input) ?: return null
        }
        if (length < 0) return null
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) throw EOFException("LSP frame ended after $offset of $length bytes")
            offset += read
        }
        return payload.toString(UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        var next = input.read()
        if (next < 0) return null
        while (next >= 0 && next != '\n'.code) {
            if (next != '\r'.code) line.append(next.toChar())
            next = input.read()
        }
        return line.toString()
    }
}

internal class LspConnection(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val mutableNotifications = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var reader: Job? = null
    @Volatile private var closing = false

    val notifications: SharedFlow<JsonObject> = mutableNotifications

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        close()
        val connected = Socket(host, port).apply { tcpNoDelay = true }
        socket = connected
        output = BufferedOutputStream(connected.getOutputStream())
        reader = scope.launch(Dispatchers.IO) {
            val input = BufferedInputStream(connected.getInputStream())
            try {
                while (true) {
                    val text = LspFrameCodec.read(input) ?: throw EOFException("Stratum service closed the connection")
                    val message = json.parseToJsonElement(text).jsonObject
                    val id = message["id"]?.jsonPrimitive?.content?.toLongOrNull()
                    if (id != null && (message.containsKey("result") || message.containsKey("error"))) {
                        pending.remove(id)?.complete(message["result"] ?: message["error"] ?: JsonNull)
                    } else if (message.containsKey("method")) {
                        mutableNotifications.emit(message)
                    }
                }
            } catch (failure: IOException) {
                if (!closing) pending.values.forEach { it.completeExceptionally(failure) }
            } finally {
                pending.values.forEach { it.cancel() }
                pending.clear()
            }
        }
        request("initialize", buildJsonObject { put("capabilities", buildJsonObject {}) })
        notify("initialized", buildJsonObject {})
    }

    suspend fun request(method: String, params: JsonElement): JsonElement {
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        })
        return result.await()
    }

    suspend fun notify(method: String, params: JsonElement) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        closing = true
        try {
            socket?.close()
            reader?.cancelAndJoin()
            reader = null
            output = null
            socket = null
        } finally {
            closing = false
        }
    }

    private suspend fun send(message: JsonObject) = withContext(Dispatchers.IO) {
        val stream = checkNotNull(output) { "not connected" }
        synchronized(stream) { LspFrameCodec.write(stream, json.encodeToString(JsonObject.serializer(), message)) }
    }
}