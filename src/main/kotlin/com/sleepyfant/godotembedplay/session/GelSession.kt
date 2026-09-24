package com.sleepyfant.godotembedplay.session

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One running Godot process: owns the localhost server socket the shim connects back to,
 * decodes incoming frames into [BufferedImage]s and encodes outgoing input messages.
 *
 * Protocol constants mirror `godot/gel_shim.gd`.
 */
class GelSession(val title: String) : Disposable {

    interface Listener {
        fun onConnected(hello: String)
        fun onFrame(image: BufferedImage)
        fun onStats(streamFps: Int, gameFps: Float, width: Int, height: Int)
        fun onDisconnected(reason: String)
        /** Godot Input.mouse_mode: 0 visible, 1 hidden, 2 captured, 3 confined, 4 confined hidden. */
        fun onMouseMode(mode: Int) {}
        /** Godot DisplayServer.CursorShape (embedded mode only). */
        fun onCursorShape(shape: Int) {}
        /** Debugger link established; [relayed] = Godot editor (and Rider through it) debugs the game. */
        fun onDebuggerAttached(relayed: Boolean) {}
        fun onDebugPaused(reason: String) {}
        fun onDebugResumed() {}
    }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    @Volatile var processHandler: ProcessHandler? = null
    /** Present when Godot runs with --embedded (macOS). Closed together with the session. */
    @Volatile var debugServer: GodotDebugServer? = null
        set(value) {
            field = value
            value?.listener = object : GodotDebugServer.Listener {
                override fun onCursorShape(shape: Int) { listener?.onCursorShape(shape) }
                override fun onMouseMode(mode: Int) { listener?.onMouseMode(mode) }
                override fun onDebuggerAttached(relayed: Boolean) { listener?.onDebuggerAttached(relayed) }
                override fun onDebugPaused(reason: String) { listener?.onDebugPaused(reason) }
                override fun onDebugResumed() { listener?.onDebugResumed() }
            }
        }
    @Volatile var listener: Listener? = null

    private val closed = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    private val outQueue = LinkedBlockingQueue<ByteArray>()
    private val images = arrayOfNulls<BufferedImage>(3)
    private var imageIndex = 0

    val isConnected: Boolean get() = socket?.isConnected == true && !closed.get()

    fun start() {
        val t = Thread(::acceptLoop, "gel-accept-$port")
        t.isDaemon = true
        t.start()
    }

    private fun acceptLoop() {
        val s: Socket = try {
            server.soTimeout = 60_000
            server.accept()
        } catch (e: SocketTimeoutException) {
            fail("Godot did not connect back within 60 s")
            return
        } catch (e: IOException) {
            if (!closed.get()) fail("accept failed: ${e.message}")
            return
        } finally {
            runCatching { server.close() }
        }
        s.tcpNoDelay = true
        s.receiveBufferSize = 4 shl 20
        socket = s
        val writer = Thread({ writeLoop(s.getOutputStream()) }, "gel-writer-$port")
        writer.isDaemon = true
        writer.start()
        try {
            readLoop(DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 20)))
        } catch (e: EOFException) {
            fail("Godot closed the connection")
        } catch (e: IOException) {
            if (!closed.get()) fail("read failed: ${e.message}")
        } catch (e: Throwable) {
            LOG.warn("gel reader crashed", e)
            fail("reader error: $e")
        }
    }

    private fun readLoop(input: DataInputStream) {
        var frames = 0
        var lastStats = System.nanoTime()
        var scratch = ByteArray(0)
        while (!closed.get()) {
            when (val type = input.readUnsignedByte()) {
                MSG_FRAME -> {
                    val w = input.readIntLE()
                    val h = input.readIntLE()
                    val format = input.readUnsignedByte()
                    val gameFps = java.lang.Float.intBitsToFloat(input.readIntLE())
                    val len = input.readIntLE()
                    require(w in 1..16384 && h in 1..16384 && len == w * h * 4 && format == 0) {
                        "bad frame header w=$w h=$h fmt=$format len=$len"
                    }
                    if (scratch.size < len) scratch = ByteArray(len)
                    input.readFully(scratch, 0, len)
                    val img = toImage(scratch, w, h)
                    sendAck()
                    listener?.onFrame(img)
                    frames++
                    val now = System.nanoTime()
                    if (now - lastStats >= 1_000_000_000L) {
                        listener?.onStats(frames, gameFps, w, h)
                        frames = 0
                        lastStats = now
                    }
                }
                MSG_HELLO -> {
                    val len = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xFFFF
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    listener?.onConnected(String(bytes, Charsets.UTF_8))
                }
                MSG_MOUSE_MODE -> listener?.onMouseMode(input.readUnsignedByte())
                else -> throw IOException("unknown message type 0x${type.toString(16)}")
            }
        }
    }

    /** RGBA8 bytes -> TYPE_INT_RGB, rotating through a small ring so the EDT can paint the previous one. */
    private fun toImage(rgba: ByteArray, w: Int, h: Int): BufferedImage {
        var img = images[imageIndex]
        if (img == null || img.width != w || img.height != h) {
            img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            images[imageIndex] = img
        }
        imageIndex = (imageIndex + 1) % images.size
        val px = (img.raster.dataBuffer as DataBufferInt).data
        var si = 0
        for (i in 0 until w * h) {
            px[i] = ((rgba[si].toInt() and 0xFF) shl 16) or
                ((rgba[si + 1].toInt() and 0xFF) shl 8) or
                (rgba[si + 2].toInt() and 0xFF)
            si += 4
        }
        return img
    }

    private fun writeLoop(raw: OutputStream) {
        val out = BufferedOutputStream(raw, 1 shl 16)
        try {
            while (!closed.get()) {
                val first = outQueue.take()
                if (first === POISON) return
                out.write(first)
                var next = outQueue.poll()
                while (next != null && next !== POISON) {
                    out.write(next)
                    next = outQueue.poll()
                }
                out.flush()
            }
        } catch (e: IOException) {
            if (!closed.get()) fail("write failed: ${e.message}")
        } catch (e: InterruptedException) {
            // closing
        }
    }

    private fun fail(reason: String) {
        if (closed.compareAndSet(false, true)) {
            LOG.info("gel session '$title': $reason")
            runCatching { socket?.close() }
            runCatching { debugServer?.close() }
            outQueue.offer(POISON)
            listener?.onDisconnected(reason)
        }
    }

    // ------------------------------------------------------------ outgoing

    private fun send(bytes: ByteArray) {
        if (!closed.get()) outQueue.offer(bytes)
    }

    private inline fun msg(size: Int, build: ByteBuffer.() -> Unit): ByteArray {
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.build()
        return bb.array()
    }

    fun sendResize(w: Int, h: Int) = send(msg(9) { put(MSG_RESIZE.toByte()); putInt(w); putInt(h) })

    fun sendMouseMove(x: Float, y: Float, relX: Float, relY: Float, buttonMask: Int, mods: Int) =
        send(msg(19) {
            put(MSG_MOUSE_MOVE.toByte()); putFloat(x); putFloat(y); putFloat(relX); putFloat(relY)
            put(buttonMask.toByte()); put(mods.toByte())
        })

    fun sendMouseButton(x: Float, y: Float, button: Int, pressed: Boolean, double: Boolean, mods: Int) =
        send(msg(13) {
            put(MSG_MOUSE_BUTTON.toByte()); putFloat(x); putFloat(y)
            put(button.toByte()); put(if (pressed) 1 else 0); put(if (double) 1 else 0); put(mods.toByte())
        })

    fun sendWheel(x: Float, y: Float, dx: Float, dy: Float, mods: Int) =
        send(msg(18) { put(MSG_WHEEL.toByte()); putFloat(x); putFloat(y); putFloat(dx); putFloat(dy); put(mods.toByte()) })

    fun sendKey(vk: Int, unicode: Int, pressed: Boolean, echo: Boolean, mods: Int, location: Int) =
        send(msg(13) {
            put(MSG_KEY.toByte()); putInt(vk); putInt(unicode)
            put(if (pressed) 1 else 0); put(if (echo) 1 else 0); put(mods.toByte()); put(location.toByte())
        })

    fun sendFocus(focused: Boolean) = send(msg(2) { put(MSG_FOCUS.toByte()); put(if (focused) 1 else 0) })

    private fun sendAck() = send(ACK)

    fun sendQuit() = send(QUIT)

    // ------------------------------------------------------------ lifecycle

    /** Ask Godot to quit politely, then kill the process if it lingers. */
    fun close() {
        if (closed.get()) {
            processHandler?.let { if (!it.isProcessTerminated) it.destroyProcess() }
            return
        }
        sendQuit()
        val handler = processHandler
        Thread({
            Thread.sleep(1500)
            if (handler != null && !handler.isProcessTerminated) handler.destroyProcess()
            fail("closed by IDE")
        }, "gel-close-$port").apply { isDaemon = true }.start()
    }

    /** Called when the Godot process is gone (exit or kill): tear the socket side down. */
    fun onProcessExited(exitCode: Int) = fail("Godot exited with code $exitCode")

    override fun dispose() = close()

    private fun DataInputStream.readIntLE(): Int = Integer.reverseBytes(readInt())

    companion object {
        private val LOG = logger<GelSession>()
        const val MSG_RESIZE = 0x01
        const val MSG_MOUSE_MOVE = 0x02
        const val MSG_MOUSE_BUTTON = 0x03
        const val MSG_WHEEL = 0x04
        const val MSG_KEY = 0x05
        const val MSG_FOCUS = 0x06
        const val MSG_ACK = 0x07
        const val MSG_QUIT = 0x08
        const val MSG_FRAME = 0x81
        const val MSG_HELLO = 0x82
        const val MSG_MOUSE_MODE = 0x83
        private val ACK = byteArrayOf(MSG_ACK.toByte())
        private val QUIT = byteArrayOf(MSG_QUIT.toByte())
        private val POISON = ByteArray(0)
    }
}
