package com.sleepyfant.godotembedplay.session

import com.intellij.openapi.diagnostic.logger
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The game's remote debugger endpoint (`godot --remote-debug tcp://127.0.0.1:<port>`).
 *
 * On macOS the plugin starts Godot with `--embedded`, which needs a debugger connection to run at all.
 *
 * **Relay mode** (Godot editor debug server reachable on [editorPort], e.g. with
 * *Debug → Keep Debug Server Open*): every packet is forwarded both ways, so the editor, and Rider's
 * GDScript debugger attached to it via DAP, owns breakpoints, stepping and variables. Only the
 * `game_view:*` / `embed:*` traffic is kept out: that is the editor's own Game-view embedding protocol
 * and would make the editor try to host this game. The plugin just watches `debug_enter` / `debug_exit`
 * to show the paused state.
 *
 * **Standalone mode** (no editor): tells the game to skip `breakpoint` statements and not break on script
 * errors, and answers any `debug_enter` with `continue`, since nothing could resume a break.
 *
 * Wire format (core/debugger/remote_debugger_peer.cpp, core/io/marshalls.cpp, Godot 4.7):
 * every packet is `u32 size` + a Variant-encoded Array `[message: String, thread_id: int, data: Array]`,
 * little-endian, in both directions.
 */
class GodotDebugServer(private val title: String, private val editorPort: Int) {

    interface Listener {
        fun onCursorShape(shape: Int) {}
        fun onMouseMode(mode: Int) {}
        /** [relayed]: true = Godot editor (and Rider through it) debugs this game; false = standalone. */
        fun onDebuggerAttached(relayed: Boolean) {}
        fun onDebugPaused(reason: String) {}
        fun onDebugResumed() {}
    }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    @Volatile var listener: Listener? = null

    private val closed = AtomicBoolean(false)
    @Volatile private var game: Socket? = null
    @Volatile private var editor: Socket? = null
    private var gameOut: OutputStream? = null
    private var editorOut: OutputStream? = null
    private var mainThread: Long = -1
    @Volatile private var paused = false
    /** null until the game connected; then whether the editor relay is up. */
    @Volatile var relayed: Boolean? = null
        private set

    fun start() {
        Thread(::run, "gel-debugger-$port").apply { isDaemon = true }.start()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            runCatching { game?.close() }
            runCatching { editor?.close() }
            if (paused) {
                paused = false
                listener?.onDebugResumed()
            }
        }
    }

    private fun run() {
        try {
            server.soTimeout = 60_000
            val s = server.accept()
            game = s
            s.tcpNoDelay = true
            gameOut = s.getOutputStream()
            connectEditor()
            relayed = editor != null
            listener?.onDebuggerAttached(editor != null)
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            while (!closed.get()) {
                val packet = readPacket(input)
                if (fromGame(packet)) editorOut?.let { write(it, packet, editorLock) }
            }
        } catch (_: SocketTimeoutException) {
            LOG.info("gel debugger '$title': Godot did not connect")
        } catch (e: IOException) {
            if (!closed.get()) LOG.info("gel debugger '$title': ${e.message}")
        } catch (e: Throwable) {
            LOG.warn("gel debugger '$title' crashed", e)
        } finally {
            close()
        }
    }

    private fun connectEditor() {
        if (editorPort <= 0) return
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), editorPort), 300)
        } catch (_: IOException) {
            runCatching { sock.close() }
            LOG.info("gel debugger '$title': no Godot editor debug server on $editorPort, standalone mode")
            return
        }
        sock.tcpNoDelay = true
        editor = sock
        editorOut = sock.getOutputStream()
        Thread({
            try {
                val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
                while (!closed.get()) {
                    val packet = readPacket(input)
                    if (fromEditor(packet)) gameOut?.let { write(it, packet, gameLock) }
                }
            } catch (e: IOException) {
                if (!closed.get()) LOG.info("gel debugger '$title': editor link closed: ${e.message}")
            } finally {
                // Editor went away (closed, or its session stopped). Dropping the game's debugger link too
                // makes a paused game resume (RemoteDebugger's break loop exits on disconnect); it keeps running.
                if (!closed.get()) close()
            }
        }, "gel-debugger-editor-$port").apply { isDaemon = true }.start()
    }

    private fun readPacket(input: DataInputStream): ByteArray {
        val size = Integer.reverseBytes(input.readInt())
        if (size < 0 || size > (64 shl 20)) throw IOException("bad packet size $size")
        val buf = ByteArray(size)
        input.readFully(buf)
        return buf
    }

    private fun decode(packet: ByteArray): Triple<String, Long, List<*>>? {
        val msg = try {
            VariantReader(packet).read() as? List<*>
        } catch (_: UnsupportedVariant) {
            null  // a type we do not decode (objects, callables, ...): not one we care about
        } catch (_: RuntimeException) {
            null
        } ?: return null
        if (msg.size != 3) return null
        val name = msg[0] as? String ?: return null
        val thread = (msg[1] as? Number)?.toLong() ?: return null
        return Triple(name, thread, msg[2] as? List<*> ?: emptyList<Any?>())
    }

    /** Game -> plugin. Returns whether to forward the packet to the editor. */
    private fun fromGame(packet: ByteArray): Boolean {
        val (name, thread, data) = decode(packet) ?: return true
        val relayed = editor != null
        if (mainThread < 0) {
            // First message comes from the main thread; that is where RemoteDebugger polls commands.
            mainThread = thread
            if (!relayed) {
                send("set_skip_breakpoints", listOf(true))
                send("set_ignore_error_breaks", listOf(true))
            }
        }
        when (name) {
            "debug_enter" -> {
                if (!relayed) {
                    send("continue", emptyList(), thread)
                } else if (!paused) {
                    paused = true
                    // data: [can_continue, error, has_stackdump, thread_id]
                    val error = data.getOrNull(1) as? String
                    listener?.onDebugPaused(if (error.isNullOrBlank()) "Breakpoint" else error)
                }
            }
            "debug_exit" -> if (paused) {
                paused = false
                listener?.onDebugResumed()
            }
            "game_view:cursor_set_shape" -> (data.firstOrNull() as? Number)?.let { listener?.onCursorShape(it.toInt()) }
            "game_view:mouse_set_mode" -> (data.firstOrNull() as? Number)?.let { listener?.onMouseMode(it.toInt()) }
        }
        return !name.startsWith("game_view:")
    }

    /** Editor -> game. Returns whether to forward. */
    private fun fromEditor(packet: ByteArray): Boolean {
        val (name, _, _) = decode(packet) ?: return true
        return !name.startsWith("embed:")
    }

    private val gameLock = Any()
    private val editorLock = Any()

    private fun write(o: OutputStream, body: ByteArray, lock: Any) {
        val pkt = ByteBuffer.allocate(4 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        pkt.putInt(body.size).put(body)
        try {
            synchronized(lock) {
                o.write(pkt.array())
                o.flush()
            }
        } catch (e: IOException) {
            if (!closed.get()) LOG.info("gel debugger write failed: ${e.message}")
        }
    }

    private fun send(command: String, args: List<Any?>, thread: Long = mainThread) {
        val o = gameOut ?: return
        write(o, VariantWriter().apply { write(listOf(command, thread, args)) }.bytes(), gameLock)
    }

    // ------------------------------------------------------------------ Variant codec (subset)

    private class UnsupportedVariant(type: Int) : Exception("variant type $type")

    private class VariantReader(bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun read(): Any? {
            val header = b.int
            val type = header and 0xFF
            val is64 = header and FLAG_64 != 0
            val real = if (is64) 8 else 4
            return when (type) {
                NIL -> null
                BOOL -> b.int != 0
                INT -> if (is64) b.long else b.int.toLong()
                FLOAT -> if (is64) b.double else b.float.toDouble()
                STRING, STRING_NAME -> string()
                VECTOR2I -> listOf(b.int, b.int)
                // Fixed-size math types: skip their payload.
                VECTOR2 -> skip(2 * real)
                RECT2 -> skip(4 * real)
                RECT2I -> skip(16)
                VECTOR3 -> skip(3 * real)
                VECTOR3I -> skip(12)
                TRANSFORM2D -> skip(6 * real)
                VECTOR4 -> skip(4 * real)
                VECTOR4I -> skip(16)
                PLANE, QUATERNION -> skip(4 * real)
                AABB -> skip(6 * real)
                BASIS -> skip(9 * real)
                TRANSFORM3D -> skip(12 * real)
                PROJECTION -> skip(16 * real)
                COLOR -> skip(16)
                RID -> skip(8)
                DICTIONARY -> {
                    containerType((header shr 16) and 0b11)
                    containerType((header shr 18) and 0b11)
                    val n = b.int and 0x7FFFFFFF
                    val m = LinkedHashMap<Any?, Any?>()
                    repeat(n) { m[read()] = read() }
                    m
                }
                ARRAY -> {
                    containerType((header shr 16) and 0b11)
                    val n = b.int and 0x7FFFFFFF
                    List(n) { read() }
                }
                PACKED_BYTE_ARRAY -> { val n = b.int; skip(n); pad(n); null }
                PACKED_INT32_ARRAY, PACKED_FLOAT32_ARRAY -> skip(b.int * 4)
                PACKED_INT64_ARRAY, PACKED_FLOAT64_ARRAY -> skip(b.int * 8)
                PACKED_STRING_ARRAY -> { val n = b.int; List(n) { string() } }
                PACKED_VECTOR2_ARRAY -> skip(b.int * 2 * real)
                PACKED_VECTOR3_ARRAY -> skip(b.int * 3 * real)
                PACKED_COLOR_ARRAY -> skip(b.int * 16)
                PACKED_VECTOR4_ARRAY -> skip(b.int * 4 * real)
                else -> throw UnsupportedVariant(type)  // NODE_PATH, OBJECT, CALLABLE, SIGNAL
            }
        }

        /** Typed-container header extension: builtin -> u32 type, class name / script -> string. */
        private fun containerType(kind: Int) {
            when (kind) {
                0 -> {}
                1 -> b.int
                2, 3 -> string()
            }
        }

        private fun string(): String {
            val n = b.int
            val s = String(b.array(), b.position(), n, Charsets.UTF_8)
            b.position(b.position() + n)
            pad(n)
            return s
        }

        private fun pad(n: Int) {
            if (n % 4 != 0) b.position(b.position() + (4 - n % 4))
        }

        private fun skip(n: Int): Any? {
            b.position(b.position() + n)
            return null
        }
    }

    private class VariantWriter {
        private val b = java.io.ByteArrayOutputStream()
        private val tmp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun bytes(): ByteArray = b.toByteArray()

        private fun u32(v: Int) {
            tmp.clear(); tmp.putInt(v); b.write(tmp.array(), 0, 4)
        }

        fun write(v: Any?) {
            when (v) {
                null -> u32(NIL)
                is Boolean -> { u32(BOOL); u32(if (v) 1 else 0) }
                is Int -> { u32(INT); u32(v) }
                is Long -> {
                    if (v in Int.MIN_VALUE..Int.MAX_VALUE) {
                        u32(INT); u32(v.toInt())
                    } else {
                        u32(INT or FLAG_64); tmp.clear(); tmp.putLong(v); b.write(tmp.array(), 0, 8)
                    }
                }
                is String -> {
                    u32(STRING)
                    val s = v.toByteArray(Charsets.UTF_8)
                    u32(s.size); b.write(s)
                    repeat((4 - s.size % 4) % 4) { b.write(0) }
                }
                is List<*> -> { u32(ARRAY); u32(v.size); v.forEach { write(it) } }
                else -> error("cannot encode ${v::class}")
            }
        }
    }

    companion object {
        private val LOG = logger<GodotDebugServer>()
        private const val FLAG_64 = 1 shl 16

        // Variant::Type, Godot 4.7
        private const val NIL = 0
        private const val BOOL = 1
        private const val INT = 2
        private const val FLOAT = 3
        private const val STRING = 4
        private const val VECTOR2 = 5
        private const val VECTOR2I = 6
        private const val RECT2 = 7
        private const val RECT2I = 8
        private const val VECTOR3 = 9
        private const val VECTOR3I = 10
        private const val TRANSFORM2D = 11
        private const val VECTOR4 = 12
        private const val VECTOR4I = 13
        private const val PLANE = 14
        private const val QUATERNION = 15
        private const val AABB = 16
        private const val BASIS = 17
        private const val TRANSFORM3D = 18
        private const val PROJECTION = 19
        private const val COLOR = 20
        private const val STRING_NAME = 21
        private const val RID = 23
        private const val DICTIONARY = 27
        private const val ARRAY = 28
        private const val PACKED_BYTE_ARRAY = 29
        private const val PACKED_INT32_ARRAY = 30
        private const val PACKED_INT64_ARRAY = 31
        private const val PACKED_FLOAT32_ARRAY = 32
        private const val PACKED_FLOAT64_ARRAY = 33
        private const val PACKED_STRING_ARRAY = 34
        private const val PACKED_VECTOR2_ARRAY = 35
        private const val PACKED_VECTOR3_ARRAY = 36
        private const val PACKED_COLOR_ARRAY = 37
        private const val PACKED_VECTOR4_ARRAY = 38
    }
}
