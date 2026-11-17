/*
 * Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android,
 * see https://www.linhome.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.linhome.ui.call

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.Action

/**
 * The call screens' buttons: the door's first button large in the middle (the one tap that matters: open and let
 * the call time out), and everything else small.
 *
 * Two fixed controls ([Control.start] green/mute on the left, [Control.end] red on the right) and the
 * [others] (the door's other buttons; in a call also the speaker) are placed by [arrange]: with two others or
 * fewer they sit in one bottom row between the fixed pair; with more, the fixed pair flanks the large button
 * low and the others get the bottom row to themselves, centered.
 */
class CallButtonPad @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** A small round button. [off]: shown struck through (muted microphone, speaker off). */
    class Control(
        val icon: String,
        val text: String?,
        val effect: String,
        val outline: Boolean,
        val description: String,
        val onClick: () -> Unit
    ) {
        internal var iconView: ImageView? = null
        fun setOff(off: Boolean) {
            val icon = iconView ?: return
            icon.foreground = if (off) androidx.core.content.ContextCompat.getDrawable(icon.context, org.linhome.R.drawable.strike_trhough) else null
        }
    }

    enum class Arrangement { ROW, FLANKED }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /**
     * Lays the buttons out again. [hero] null: no door button at all (a call from a number that is no door),
     * then the fixed pair and the others share one row.
     */
    fun show(hero: Action?, onHero: (Action) -> Unit, start: Control, end: Control, others: List<Control>) {
        removeAllViews()
        val arrangement = arrange(hero != null, others.size)
        if (hero != null) {
            val heroView = heroButton(hero, onHero)
            if (arrangement == Arrangement.FLANKED) {
                val line = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.BOTTOM
                }
                line.addView(small(start), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                line.addView(heroView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                line.addView(small(end), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            } else {
                addView(heroView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        }
        if (arrangement == Arrangement.FLANKED) {
            // Centered as a group, not spread to the edges: they are the rare ones.
            others.forEach { row.addView(small(it), LayoutParams(dp(72), LayoutParams.WRAP_CONTENT)) }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        } else {
            (listOf(start) + others + end).forEach { row.addView(small(it), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun heroButton(action: Action, onHero: (Action) -> Unit): View {
        val accent = Theme.getColor("color_a")
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                // The halo: room around the button, so it reads as the one to press.
                setStroke(dp(10), ColorUtils.setAlphaComponent(accent, 70))
            }
            isClickable = true
            isFocusable = true
            foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                .let { a -> a.getDrawable(0).also { a.recycle() } }
            contentDescription = action.actionText() ?: action.typeName()
            setOnClickListener { onHero(action) }
        }
        val icon = ImageView(context).apply {
            imageTintList = Theme.buildSingleColorStateList("color_c")
            action.iconName()?.let { Theme.setIcon(it, this) }
        }
        circle.addView(icon, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        column.addView(circle, LayoutParams(dp(128), dp(128)))
        action.actionText()?.let { text ->
            column.addView(label(text, 15f), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        return column
    }

    private fun small(control: Control): View {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val icon = ImageView(context).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = if (control.outline) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(Theme.getColor("color_c"), 28))
                    setStroke(dp(1), ColorUtils.setAlphaComponent(Theme.getColor("color_c"), 140))
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    Theme.selectionEffectAsColorStateList(control.effect, android.R.attr.state_pressed)?.let { setColor(it) }
                }
            }
            imageTintList = Theme.buildSingleColorStateList("color_c")
            Theme.setIcon(control.icon, this)
            isClickable = true
            isFocusable = true
            contentDescription = control.description
            setOnClickListener { control.onClick() }
        }
        column.addView(icon, LayoutParams(dp(56), dp(56)))
        control.text?.let { column.addView(label(it, 12f), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }) }
        control.iconView = icon
        return column
    }

    private fun label(text: String, sp: Float) = TextView(context).apply {
        Theme.apply("call_action_button", this)
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }

    companion object {
        /** At most two others share the row with the fixed pair. */
        const val ROW_OTHERS = 2

        /** The bottom row's capacity when the fixed pair moves up beside the large button. */
        const val FLANKED_OTHERS = 4

        fun arrange(hasHero: Boolean, others: Int): Arrangement =
            if (hasHero && others > ROW_OTHERS) Arrangement.FLANKED else Arrangement.ROW

        /** The door's buttons for the pad: the first is the large one, then at most [FLANKED_OTHERS] more. */
        fun split(actions: List<Action>): Pair<Action?, List<Action>> =
            actions.firstOrNull() to actions.drop(1).take(FLANKED_OTHERS)

        fun text(key: String): String = Texts.get(key)
    }
}
