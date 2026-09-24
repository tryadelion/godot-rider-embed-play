package com.sleepyfant.godotembedplay.view

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.awt.Robot

/**
 * Moves the real OS cursor (screen coordinates, logical points, AWT convention).
 *
 * macOS: CGWarpMouseCursorPosition via JNA. Needs no Accessibility permission (java.awt.Robot does,
 * since it posts synthetic events). Re-associating the mouse right after the warp avoids the
 * ~250 ms input freeze macOS applies after a warp.
 * Elsewhere: java.awt.Robot.
 */
object MouseWarp {
    private val LOG = logger<MouseWarp>()

    @Structure.FieldOrder("x", "y")
    open class CGPoint : Structure() {
        @JvmField var x: Double = 0.0
        @JvmField var y: Double = 0.0

        class ByValue : CGPoint(), Structure.ByValue
    }

    @Suppress("FunctionName")
    private interface CoreGraphics : Library {
        fun CGWarpMouseCursorPosition(point: CGPoint.ByValue): Int
        fun CGAssociateMouseAndMouseCursorPosition(connected: Int): Int
    }

    private val cg: CoreGraphics? by lazy {
        if (!SystemInfo.isMac) return@lazy null
        try {
            Native.load("CoreGraphics", CoreGraphics::class.java)
        } catch (e: Throwable) {
            LOG.warn("CoreGraphics not loadable, falling back to Robot", e)
            null
        }
    }

    private val robot: Robot? by lazy {
        try {
            Robot()
        } catch (e: Throwable) {
            LOG.warn("java.awt.Robot unavailable; mouse capture cannot re-center the cursor", e)
            null
        }
    }

    fun warp(screenX: Int, screenY: Int) {
        val g = cg
        if (g != null) {
            val p = CGPoint.ByValue()
            p.x = screenX.toDouble()
            p.y = screenY.toDouble()
            g.CGWarpMouseCursorPosition(p)
            g.CGAssociateMouseAndMouseCursorPosition(1)
            return
        }
        robot?.mouseMove(screenX, screenY)
    }
}
