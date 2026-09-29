package com.sleepyfant.godotembedplay.view

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Play Preview toolbar icons; `_dark` variants are picked up automatically. */
object GelViewIcons {
    val SCREENSHOT: Icon = load("screenshot")
    val SOUND_ON: Icon = load("soundOn")
    val SOUND_OFF: Icon = load("soundOff")
    val ROTATE: Icon = load("rotate")

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/view/$name.svg", GelViewIcons::class.java)
}
