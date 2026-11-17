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

package org.linhome.ui.history

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.databinding.BindingAdapter
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.HistoryEvent

/**
 * How a call ended, drawn with as little text as possible: a green badge with the pressed button's icon (the door
 * was opened from this phone), a name chip for who took it elsewhere, red for nobody. Shared by the snapshot
 * gallery and the History list.
 */
object CallResultBadges {

    /** What [event] shows, in order; empty for calls from before the results were kept. */
    sealed class Badge {
        data class Pressed(val icon: String) : Badge()
        data class Elsewhere(val name: String?) : Badge()
        data class Plain(val icon: String, val color: Int, val description: String) : Badge()
    }

    private const val GREEN = 0xFF2E7D4F.toInt()
    private const val RED = 0xFFB23B3B.toInt()
    private const val GREY = 0xFF6B7280.toInt()
    private const val NAME = 0xFF5B6ABF.toInt()

    fun badgesFor(event: HistoryEvent?): List<Badge> {
        if (event == null) return emptyList()
        val badges = mutableListOf<Badge>()
        event.actionIcons.distinct().take(2).forEach { badges += Badge.Pressed(it) }
        when (event.result) {
            "answered_elsewhere", "declined_elsewhere" -> badges += Badge.Elsewhere(event.answeredBy)
            "missed", "failed" -> if (badges.isEmpty()) badges += Badge.Plain("icons/missed", RED, Texts.get("call_result_missed"))
            "declined" -> if (badges.isEmpty()) badges += Badge.Plain("icons/declined", GREY, Texts.get("call_result_declined"))
            "answered" -> if (badges.isEmpty()) badges += Badge.Plain("icons/accepted", GREY, Texts.get("call_result_answered"))
            "previewed" -> if (badges.isEmpty()) badges += Badge.Plain("icons/eye", GREY, Texts.get("call_result_previewed"))
        }
        return badges
    }

    /** Fills [row] (horizontal) with [event]'s badges, [sizeDp] each; [withNames]: the name chip carries the name. */
    fun fill(row: LinearLayout, event: HistoryEvent?, sizeDp: Int, withNames: Boolean) {
        row.removeAllViews()
        val context = row.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val size = dp(sizeDp)
        badgesFor(event).forEachIndexed { index, badge ->
            val view = when (badge) {
                is Badge.Pressed -> circle(row, badge.icon, GREEN, size, Texts.get("call_result_door_action"))
                is Badge.Plain -> circle(row, badge.icon, badge.color, size, badge.description)
                is Badge.Elsewhere -> if (withNames && badge.name != null) {
                    TextView(context).apply {
                        text = badge.name
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.5f)
                        maxLines = 1
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), 0, dp(10), 0)
                        background = GradientDrawable().apply { setColor(NAME); cornerRadius = size / 2f }
                        contentDescription = Texts.get("call_answered_elsewhere_by_banner", badge.name)
                    }
                } else {
                    // A small person mark: the name is in the row's text.
                    circle(row, "icons/account", NAME, size, badge.name?.let { Texts.get("call_answered_elsewhere_by_banner", it) } ?: Texts.get("call_result_elsewhere"))
                }
            }
            val params = LinearLayout.LayoutParams(if (view is TextView) LinearLayout.LayoutParams.WRAP_CONTENT else size, size)
            if (index > 0) params.marginStart = dp(4)
            row.addView(view, params)
        }
    }

    /** The name of who took the call elsewhere, for a row's text; null when there is none. */
    fun elsewhereName(event: HistoryEvent?): String? =
        event?.takeIf { it.result == "answered_elsewhere" || it.result == "declined_elsewhere" }?.answeredBy

    private fun circle(row: LinearLayout, icon: String, color: Int, size: Int, description: String): FrameLayout {
        val context = row.context
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((size / 12).coerceAtLeast(1), Color.WHITE)
            }
            contentDescription = description
        }
        val image = ImageView(context).apply {
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            Theme.setIcon(icon, this)
        }
        val inner = (size * 0.62f).toInt()
        frame.addView(image, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
        return frame
    }
}

@BindingAdapter("callResult")
fun callResult(row: LinearLayout, event: HistoryEvent?) {
    CallResultBadges.fill(row, event, 22, withNames = false)
}

/** Tapping a call's snapshot in History opens its door's gallery there (not while selecting rows to delete). */
@BindingAdapter("galleryFor")
fun galleryFor(view: android.view.View, model: HistoryEventViewModel?) {
    val device = model?.device
    val callId = model?.callLog?.callId
    if (device == null || callId == null || model.historyEvent?.hasMediaThumbnail() != true) {
        view.setOnClickListener(null)
        view.isClickable = false
        return
    }
    view.setOnClickListener {
        if (model.historyViewModel.editing.value != true) {
            org.linhome.ui.devices.SnapshotViewer.show(view.context, device, callId)
        }
    }
}
