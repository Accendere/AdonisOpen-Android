/*
 * Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android).
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
package org.linhome.ui.devices

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.ui.widgets.LRoundRectButton

/**
 * The end of the door list: a round "+" centred under the last door, or, with no doors yet, a larger
 * "Add a door" block. Replaces the floating "+".
 */
class AddDoorFooterAdapter(private val onAdd: () -> Unit) : RecyclerView.Adapter<AddDoorFooterAdapter.Holder>() {

    /** No doors: the larger empty state. */
    var empty: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemChanged(0)
            }
        }

    class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    override fun getItemCount() = 1

    override fun getItemViewType(position: Int) = if (empty) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val frame = holder.frame
        frame.removeAllViews()
        val context = frame.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        if (!empty) {
            val plus = ImageView(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Theme.getColor("color_a"))
                }
                Theme.setIcon("icons/more", this)
                imageTintList = Theme.buildSingleColorStateList("color_c")
                setPadding(dp(14), dp(14), dp(14), dp(14))
                contentDescription = Texts.get("devices_add")
                isClickable = true
                isFocusable = true
                elevation = dp(2).toFloat()
                setOnClickListener { onAdd() }
            }
            frame.setPadding(0, dp(12), 0, dp(24))
            frame.addView(plus, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER_HORIZONTAL))
            return
        }
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            addView(TextView(context).apply {
                text = Texts.get("devices_empty_list_title")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Theme.getColor("color_g"))
                gravity = Gravity.CENTER
            })
            addView(LRoundRectButton(context).apply {
                Theme.roundRectButtonBackgroundStates("primary_color")?.also { background = it }
                buttonText.text = Texts.get("devices_add")
                isClickable = true
                isFocusable = true
                setOnClickListener { onAdd() }
            }, LinearLayout.LayoutParams(dp(260), dp(52)).apply { topMargin = dp(20) })
        }
        frame.addView(block, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
