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

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.signature.ObjectKey
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.Device
import org.linhome.ui.widgets.ZoomableImageView
import org.linhome.utils.extensions.existsAndIsNotEmpty

/**
 * A door's last snapshot over the whole app (the status and navigation bars stay), pinch to zoom, double tap to zoom
 * in or out. Closed with the X or Back.
 */
object SnapshotViewer {

    /** Opens [device]'s snapshot; does nothing when it has none. */
    fun show(context: Context, device: Device) {
        val thumb = device.thumbNail
        if (!thumb.existsAndIsNotEmpty()) return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
        val image = ZoomableImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = device.name
        }
        Theme.glidegeneric.load(thumb).signature(ObjectKey(thumb.lastModified())).into(image)

        val close = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = Texts.get("snapshot_close")
            setOnClickListener { dialog.dismiss() }
        }
        val caption = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(64), dp(12))
            setBackgroundColor(0x99000000.toInt())
            addView(TextView(context).apply {
                text = device.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(TextView(context).apply {
                text = DateUtils.getRelativeDateTimeString(
                    context, thumb.lastModified(), DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0
                )
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }
}
