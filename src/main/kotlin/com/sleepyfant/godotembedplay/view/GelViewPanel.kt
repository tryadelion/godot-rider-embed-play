package com.sleepyfant.godotembedplay.view

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sleepyfant.godotembedplay.session.GelSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.MouseInputAdapter
import kotlin.math.abs

/**
 * Tool-window tab: the streamed Godot frame plus a thin status bar.
 *
 * Input is hover-based:
 *  - pointer over the view -> the view takes keyboard focus; mouse and keys go to the game;
 *  - pointer leaves -> focus returns to where it was, held keys are released, the game gets nothing;
 *  - a mouse button pressed inside anchors a gesture: while any button is held, motion keeps flowing even
 *    outside the view; on the last release the cursor jumps back to the anchor.
 *
 * The cursor is never hidden or locked. The game's mouse mode is ignored (in embedded mode Godot does not
 * touch the real cursor either); only its cursor shape is mirrored.
 */
class GelViewPanel(
    private val project: Project,
    private val session: GelSession,
    private val hiDpi: Boolean,
) : JPanel(BorderLayout()), GelSession.Listener {

    private val canvas = Canvas()
    private val status = JBLabel("Waiting for Godot to connect…")
    private val hint = JBLabel("").apply { foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY) }
    private val stopButton = JButton("Stop")

    @Volatile private var image: BufferedImage? = null
    @Volatile private var stopped = false
    // Where the last image was drawn (logical px) and its size in render px; used to map the mouse back.
    @Volatile private var drawX = 0.0
    @Volatile private var drawY = 0.0
    @Volatile private var drawW = 1.0
    @Volatile private var drawH = 1.0
    @Volatile private var imgW = 1
    @Volatile private var imgH = 1
    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private val pressedKeys = HashSet<Int>()
    private val resizeTimer = Timer(120) { pushSize() }.apply { isRepeats = false }
    private var lastSentW = -1
    private var lastSentH = -1
    private var activated = false

    // ---- hover focus + gesture anchor (EDT only)
    /** Component that had focus before the pointer entered the view; gets it back on exit. */
    private var previousFocus: WeakReference<Component>? = null
    /** Screen point (logical) of the press that started the current gesture; null when no button is held. */
    private var anchor: Point? = null
    private var heldButtons = 0

    // ---- debugger pause (EDT only)
    private var paused = false
    private var pauseReason = ""
    private var blurred: BufferedImage? = null
    private val debugLabel = JBLabel("").apply { foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY) }

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
        bar.add(stopButton)
        bar.add(status)
        bar.add(hint)
        bar.add(debugLabel)
        add(bar, BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
        stopButton.addActionListener { session.close() }
        session.listener = this
    }

    /** Render scale: physical pixels per logical pixel when HiDPI rendering is on, else 1. */
    private fun scale(): Float = if (hiDpi) JBUIScale.sysScale(canvas) else 1f

    private fun pushSize() {
        val s = scale()
        val w = (canvas.width * s).toInt()
        val h = (canvas.height * s).toInt()
        if (w <= 0 || h <= 0 || (w == lastSentW && h == lastSentH)) return
        lastSentW = w
        lastSentH = h
        session.sendResize(w, h)
    }

    // ------------------------------------------------------------ session callbacks (background threads)

    override fun onConnected(hello: String) {
        ApplicationManager.getApplication().invokeLater {
            status.text = hello
            session.debugServer?.relayed?.let { showDebugger(it) }
            lastSentW = -1
            pushSize()
            bringIdeBack()
        }
    }

    override fun onFrame(image: BufferedImage) {
        if (stopped) return
        this.image = image
        canvas.repaint()
    }

    override fun onStats(streamFps: Int, gameFps: Float, width: Int, height: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (stopped) return@invokeLater
            status.text = "game ${gameFps.toInt()} fps · stream $streamFps fps · ${width}×${height}" +
                if (hiDpi) " (HiDPI)" else ""
        }
    }

    override fun onDisconnected(reason: String) {
        stopped = true
        image = null
        ApplicationManager.getApplication().invokeLater {
            status.text = "Stopped: $reason"
            stopButton.isEnabled = false
            anchor = null
            heldButtons = 0
            paused = false
            blurred = null
            debugLabel.text = ""
            canvas.cursor = Cursor.getDefaultCursor()
            if (canvas.isFocusOwner) giveFocusBack()
            updateHint()
            canvas.repaint()
        }
    }

    override fun onCursorShape(shape: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (!stopped) canvas.cursor = shapeCursor(shape)
        }
    }

    override fun onDebuggerAttached(relayed: Boolean) {
        ApplicationManager.getApplication().invokeLater { showDebugger(relayed) }
    }

    private fun showDebugger(relayed: Boolean) {
        if (stopped) return
        debugLabel.text = if (relayed) "· breakpoints: Godot editor / Rider" else "· breakpoints off (no Godot editor debug server)"
    }

    override fun onDebugPaused(reason: String) {
        ApplicationManager.getApplication().invokeLater {
            if (stopped) return@invokeLater
            paused = true
            pauseReason = reason
            blurred = image?.let(::blur)
            // The game is frozen; release whatever it thinks is held so nothing sticks after resuming.
            for (vk in pressedKeys.toList()) session.sendKey(vk, 0, false, false, 0, 0)
            pressedKeys.clear()
            canvas.repaint()
        }
    }

    override fun onDebugResumed() {
        ApplicationManager.getApplication().invokeLater {
            paused = false
            blurred = null
            canvas.repaint()
        }
    }

    /** Cheap strong blur: shrink twice with bilinear filtering; drawing it back up blurs again. */
    private fun blur(src: BufferedImage): BufferedImage {
        fun shrink(img: BufferedImage, f: Int): BufferedImage {
            val out = BufferedImage(maxOf(1, img.width / f), maxOf(1, img.height / f), BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, 0, 0, out.width, out.height, null)
            g.dispose()
            return out
        }
        return shrink(shrink(src, 4), 4)
    }

    /** Godot's process activates itself on macOS at startup (non-embedded mode); pull the IDE back once. */
    private fun bringIdeBack() {
        if (activated) return
        activated = true
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                Desktop.getDesktop().requestForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ hover focus

    private fun takeFocus() {
        if (stopped || canvas.isFocusOwner) return
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        previousFocus = if (owner != null && owner !== canvas) WeakReference(owner) else null
        canvas.requestFocusInWindow()
    }

    private fun giveFocusBack() {
        if (!canvas.isFocusOwner) return
        val prev = previousFocus?.get()
        previousFocus = null
        if (prev != null && prev.isShowing && prev.isFocusable) {
            prev.requestFocusInWindow()
        } else {
            ToolWindowManager.getInstance(project).activateEditorComponent()
        }
    }

    private fun pointerInsideView(): Boolean {
        if (!canvas.isShowing) return false
        val p = MouseInfo.getPointerInfo()?.location ?: return false
        SwingUtilities.convertPointFromScreen(p, canvas)
        return canvas.contains(p)
    }

    private fun updateHint() {
        hint.text = if (!stopped && canvas.isFocusOwner) "● input → game" else ""
    }

    // ------------------------------------------------------------ canvas

    private inner class Canvas : JComponent() {
        init {
            isFocusable = true
            focusTraversalKeysEnabled = false  // Tab reaches Godot
            background = Color(0x1e1e1e)
            isOpaque = true
            val mouse = object : MouseInputAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    takeFocus()  // also covers hovering while the IDE window was inactive
                    motion(e)
                }
                override fun mouseDragged(e: MouseEvent) = motion(e)
                override fun mousePressed(e: MouseEvent) {
                    takeFocus()
                    button(e, true)
                }
                override fun mouseReleased(e: MouseEvent) = button(e, false)
                override fun mouseEntered(e: MouseEvent) {
                    // A drag that started elsewhere (e.g. selecting text in the editor) passing over: ignore.
                    if (anchor == null && buttonMask(e) != 0) return
                    takeFocus()
                    motion(e)
                }
                override fun mouseExited(e: MouseEvent) {
                    if (anchor != null) return        // gesture in progress: keep going outside
                    if (pointerInsideView()) return   // late exit after the release warp put us back inside
                    giveFocusBack()
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener(MouseWheelListener { e -> wheel(e) })
            addKeyListener(object : KeyListener {
                override fun keyTyped(e: KeyEvent) {}
                override fun keyPressed(e: KeyEvent) = key(e, true)
                override fun keyReleased(e: KeyEvent) = key(e, false)
            })
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) {
                    session.sendFocus(true)
                    updateHint()
                }
                override fun focusLost(e: FocusEvent) {
                    for (vk in pressedKeys.toList()) session.sendKey(vk, 0, false, false, 0, 0)
                    pressedKeys.clear()
                    session.sendFocus(false)
                    updateHint()
                }
            })
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = resizeTimer.restart()
                override fun componentShown(e: ComponentEvent) = resizeTimer.restart()
            })
        }

        override fun paintComponent(g: Graphics) {
            g.color = background
            g.fillRect(0, 0, width, height)
            val img = image
            if (img == null) {
                val text = if (stopped) "Not running" else "Waiting for Godot…"
                g.color = UIUtil.getInactiveTextColor()
                g.font = JBUI.Fonts.label()
                val fm = g.fontMetrics
                g.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent) / 2)
                return
            }
            val g2 = g as Graphics2D
            // Aspect-fit and center (letterboxing for "keep" stretch or "viewport" mode).
            // In the common case the image is exactly panel size × scale, so this is a 1:1 blit
            // onto physical pixels and no filtering happens.
            val s = scale().toDouble()
            val fit = minOf(width * s / img.width, height * s / img.height)
            val w = img.width * fit / s
            val h = img.height * fit / s
            val x = (width - w) / 2
            val y = (height - h) / 2
            drawX = x; drawY = y; drawW = w; drawH = h; imgW = img.width; imgH = img.height
            val exact = abs(fit - 1.0) < 1e-3
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                if (exact) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val at = java.awt.geom.AffineTransform.getTranslateInstance(x, y)
            at.scale(w / img.width, h / img.height)
            val b = blurred
            if (paused && b != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(b, x.toInt(), y.toInt(), w.toInt(), h.toInt(), null)
                paintPause(g2, x, y, w, h)
            } else {
                g2.drawImage(img, at, null)
                if (paused) paintPause(g2, x, y, w, h)
            }
        }

        private fun paintPause(g2: Graphics2D, x: Double, y: Double, w: Double, h: Double) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = Color(0, 0, 0, 110)
            g2.fillRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
            val cx = (x + w / 2).toInt()
            val cy = (y + h / 2).toInt()
            val r = JBUI.scale(34)
            g2.color = Color(255, 255, 255, 40)
            g2.fillOval(cx - r, cy - r - JBUI.scale(18), 2 * r, 2 * r)
            g2.color = Color(255, 255, 255, 235)
            val barW = JBUI.scale(9)
            val barH = JBUI.scale(30)
            val gap = JBUI.scale(8)
            val top = cy - JBUI.scale(18) - barH / 2
            val arc = JBUI.scale(4)
            g2.fillRoundRect(cx - gap / 2 - barW, top, barW, barH, arc, arc)
            g2.fillRoundRect(cx + gap / 2, top, barW, barH, arc, arc)
            g2.font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.scaleFontSize(15f).toFloat())
            var fm = g2.fontMetrics
            val title = "Paused in debugger"
            var ty = cy + r - JBUI.scale(18) + JBUI.scale(26)
            g2.drawString(title, cx - fm.stringWidth(title) / 2, ty)
            g2.font = JBUI.Fonts.label()
            fm = g2.fontMetrics
            g2.color = Color(255, 255, 255, 190)
            for (line in listOf(pauseReason.take(120), "Continue or step from Rider's debugger")) {
                if (line.isBlank()) continue
                ty += fm.height + JBUI.scale(2)
                g2.drawString(line, cx - fm.stringWidth(line) / 2, ty)
            }
        }
    }

    // ------------------------------------------------------------ input mapping

    private fun mods(e: InputEvent): Int {
        val m = e.modifiersEx
        var r = 0
        if (m and InputEvent.SHIFT_DOWN_MASK != 0) r = r or 1
        if (m and InputEvent.CTRL_DOWN_MASK != 0) r = r or 2
        if (m and InputEvent.ALT_DOWN_MASK != 0) r = r or 4
        if (m and InputEvent.META_DOWN_MASK != 0) r = r or 8
        return r
    }

    private fun buttonMask(e: InputEvent): Int {
        val m = e.modifiersEx
        var r = 0
        if (m and InputEvent.BUTTON1_DOWN_MASK != 0) r = r or 1   // left
        if (m and InputEvent.BUTTON3_DOWN_MASK != 0) r = r or 2   // right
        if (m and InputEvent.BUTTON2_DOWN_MASK != 0) r = r or 4   // middle
        return r
    }

    private fun godotButton(e: MouseEvent): Int = when (e.button) {
        MouseEvent.BUTTON1 -> 1
        MouseEvent.BUTTON3 -> 2
        MouseEvent.BUTTON2 -> 3
        4 -> 8   // X1
        5 -> 9   // X2
        else -> 0
    }

    /** Panel (logical px) -> SubViewport render px, through the last draw rectangle. */
    private fun mapX(px: Int): Float = ((px - drawX) * imgW / drawW).toFloat()
    private fun mapY(py: Int): Float = ((py - drawY) * imgH / drawH).toFloat()

    private fun motion(e: MouseEvent) {
        if (stopped || paused || !canvas.isFocusOwner) return
        // During a gesture positions may lie outside the viewport; Godot gets them as-is, plus relative motion.
        val x = mapX(e.x)
        val y = mapY(e.y)
        session.sendMouseMove(x, y, x - lastMouseX, y - lastMouseY, buttonMask(e), mods(e))
        lastMouseX = x
        lastMouseY = y
    }

    private fun button(e: MouseEvent, pressed: Boolean) {
        if (stopped || paused) return
        val b = godotButton(e)
        if (b == 0) return
        val bit = 1 shl b
        if (pressed) {
            if (heldButtons == 0) anchor = e.locationOnScreen
            heldButtons = heldButtons or bit
        } else {
            if (heldButtons and bit == 0) return  // the press was not ours
            heldButtons = heldButtons and bit.inv()
        }
        session.sendMouseButton(mapX(e.x), mapY(e.y), b, pressed, e.clickCount >= 2, mods(e))
        if (!pressed && heldButtons == 0) endGesture(e)
    }

    /** Last button released: put the cursor back where the gesture started. */
    private fun endGesture(e: MouseEvent) {
        val a = anchor ?: return
        anchor = null
        if (a.x != e.xOnScreen || a.y != e.yOnScreen) {
            MouseWarp.warp(a.x, a.y)
            val local = Point(a)
            SwingUtilities.convertPointFromScreen(local, canvas)
            val x = mapX(local.x)
            val y = mapY(local.y)
            session.sendMouseMove(x, y, 0f, 0f, 0, mods(e))
            lastMouseX = x
            lastMouseY = y
        }
        if (!pointerInsideView()) giveFocusBack()
    }

    private fun wheel(e: MouseWheelEvent) {
        if (stopped || paused || !canvas.isFocusOwner) return
        val amount = e.preciseWheelRotation.toFloat()
        if (amount == 0f) return
        val horizontal = e.isShiftDown
        session.sendWheel(mapX(e.x), mapY(e.y), if (horizontal) amount else 0f, if (horizontal) 0f else amount, mods(e))
    }

    private fun key(e: KeyEvent, pressed: Boolean) {
        if (stopped || paused) return
        val vk = e.keyCode
        if (vk == KeyEvent.VK_UNDEFINED) return
        val echo = pressed && !pressedKeys.add(vk)
        if (!pressed) pressedKeys.remove(vk)
        val ch = e.keyChar
        val unicode = if (ch != KeyEvent.CHAR_UNDEFINED && ch.code >= 32 && ch.code != 127) ch.code else 0
        session.sendKey(vk, unicode, pressed, echo, mods(e), e.keyLocation)
        e.consume()
    }

    companion object {
        /** Godot DisplayServer.CursorShape -> AWT cursor (closest match). */
        private fun shapeCursor(shape: Int): Cursor = Cursor.getPredefinedCursor(when (shape) {
            1 -> Cursor.TEXT_CURSOR            // IBEAM
            2 -> Cursor.HAND_CURSOR            // POINTING_HAND
            3 -> Cursor.CROSSHAIR_CURSOR       // CROSS
            4, 5 -> Cursor.WAIT_CURSOR         // WAIT, BUSY
            6, 13 -> Cursor.MOVE_CURSOR        // DRAG, MOVE
            9, 14 -> Cursor.N_RESIZE_CURSOR    // VSIZE, VSPLIT
            10, 15 -> Cursor.E_RESIZE_CURSOR   // HSIZE, HSPLIT
            11 -> Cursor.NE_RESIZE_CURSOR      // BDIAGSIZE
            12 -> Cursor.NW_RESIZE_CURSOR      // FDIAGSIZE
            else -> Cursor.DEFAULT_CURSOR      // ARROW, CAN_DROP, FORBIDDEN, HELP
        })
    }
}
