package com.sleepyfant.godotembedplay.view

import kotlin.math.roundToInt

/**
 * Screen shape the game view emulates. [FIT] uses the whole panel; the others take the largest
 * rectangle of that aspect that fits both the panel's width and height. Mobile presets are
 * portrait, and [rotatable] ones can be turned sideways.
 */
enum class ViewAspect(private val label: String, private val w: Double, private val h: Double, val rotatable: Boolean = false) {
    FIT("Fit panel", 0.0, 0.0),
    ULTRAWIDE("21:9", 21.0, 9.0),
    WIDE("16:10", 16.0, 10.0),
    HD("16:9", 16.0, 9.0),
    CLASSIC("4:3", 4.0, 3.0),
    MOBILE("Standard Mobile (19.5:9)", 9.0, 19.5, rotatable = true),
    NARROW_MOBILE("Narrow Mobile (20:9)", 9.0, 20.0, rotatable = true);

    /** Render size for a panel of [availW] × [availH] px; [sideways] turns a rotatable preset to landscape. */
    fun fit(availW: Int, availH: Int, sideways: Boolean): Pair<Int, Int> {
        if (this == FIT) return availW to availH
        val ratio = if (sideways && rotatable) h / w else w / h
        return if (availW.toDouble() / availH > ratio) {
            maxOf(1, (availH * ratio).roundToInt()) to availH
        } else {
            availW to maxOf(1, (availW / ratio).roundToInt())
        }
    }

    override fun toString(): String = label
}
