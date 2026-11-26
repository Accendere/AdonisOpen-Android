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

package org.linhome.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Observer
import org.linhome.LinhomeApplication
import org.linhome.R
import org.linhome.compatibility.Compatibility
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.LinhomeAccount

/**
 * The phone's main receive-calls switch as a pill: filled in the theme's accent while taking calls, outlined while
 * paused. A tap flips it ([LinhomeAccount.setAppEnabled]), as the Doors toolbar's switch does, and it follows changes
 * made elsewhere (the toolbar, the tile, a site) while it is on screen.
 *
 * The call screens show it under the door's name; the Doors toolbar draws its own views with [style].
 */
class CallsSwitchPill @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        /**
         * Draws the pill on [container] (a 48dp tap target: the pill is 36dp high inside it) with its [icon] and
         * [label], for [enabled].
         */
        fun style(container: LinearLayout, icon: ImageView, label: TextView, enabled: Boolean) {
            val density = container.resources.displayMetrics.density
            val accent = Theme.getColor("color_a")
            // White on the accent, unless the accent is too light for it (e.g. the amber theme): then the dark text color.
            val onAccent = Theme.getColor("color_c").let { light ->
                if (ColorUtils.calculateContrast(light, accent or 0xFF000000.toInt()) >= 3.0) light
                else Theme.getColor("color_b")
            }
            val pill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                if (enabled) {
                    setColor(accent)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke((2f * density).toInt(), accent)
                }
            }
            val inset = (6f * density).toInt()
            container.background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(if (enabled) onAccent else accent, 0x40)),
                InsetDrawable(pill, 0, inset, 0, inset),
                null
            )
            val foreground = if (enabled) onAccent else accent
            icon.setImageResource(if (enabled) R.drawable.ic_qs_receive_calls else R.drawable.ic_qs_receive_calls_off)
            icon.imageTintList = ColorStateList.valueOf(foreground)
            label.setTextColor(foreground)
            label.text = Texts.get(if (enabled) "toolbar_calls_active" else "toolbar_calls_paused")
            container.contentDescription =
                Texts.get(if (enabled) "toolbar_calls_toggle_desc_active" else "toolbar_calls_toggle_desc_paused")
        }
    }

    private val icon: ImageView
    private val label: TextView
    private val observer = Observer<Boolean> { render() }

    init {
        val density = resources.displayMetrics.density
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = (96 * density).toInt()
        setPadding((14 * density).toInt(), 0, (16 * density).toInt(), 0)
        isClickable = true
        isFocusable = true
        icon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(icon, LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply { marginEnd = (7 * density).toInt() })
        label = TextView(context).apply {
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            Compatibility.vibrateActionFeedback()
            LinhomeAccount.setAppEnabled(!LinhomeApplication.corePreferences.appEnabled)
            render()
        }
        if (!isInEditMode) render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) LinhomeAccount.appEnabledLiveData.observeForever(observer)
    }

    override fun onDetachedFromWindow() {
        LinhomeAccount.appEnabledLiveData.removeObserver(observer)
        super.onDetachedFromWindow()
    }

    private fun render() = style(this, icon, label, LinhomeApplication.corePreferences.appEnabled)
}
