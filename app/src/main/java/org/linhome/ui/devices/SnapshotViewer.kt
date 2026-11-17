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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.signature.ObjectKey
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.Device
import org.linhome.entities.HistoryEvent
import org.linhome.store.DeviceStore
import org.linhome.store.HistoryEventStore
import org.linhome.ui.history.CallResultBadges
import org.linhome.ui.widgets.ZoomableImageView
import org.linhome.utils.extensions.existsAndIsNotEmpty
import java.io.File

/**
 * Full-screen gallery of a door's snapshots: its past calls, newest first, swiped left and right, each zoomable
 * and captioned with when it was and how it ended ([CallResultBadges]). A door with no call snapshot kept shows
 * its own latest image alone.
 */
object SnapshotViewer {

    /** One page: an image, when it was taken, and the call it belongs to (null for the door's own image). */
    class Page(val file: File, val atMs: Long, val event: HistoryEvent?)

    /** [device]'s pages, newest first. */
    fun pagesFor(device: Device): List<Page> {
        val pages = coreContext.core.callLogs
            .filter { log -> log.callId != null && DeviceStore.findDeviceByAddress(log.remoteAddress)?.id == device.id }
            .mapNotNull { log ->
                val event = HistoryEventStore.findHistoryEventByCallId(log.callId!!) ?: return@mapNotNull null
                if (!event.hasMediaThumbnail()) return@mapNotNull null
                Page(event.mediaThumbnail, log.startDate * 1000L, event)
            }
            .sortedByDescending { it.atMs }
        if (pages.isNotEmpty()) return pages
        val thumb = device.thumbNail
        return if (thumb.existsAndIsNotEmpty()) listOf(Page(thumb, thumb.lastModified(), null)) else emptyList()
    }

    /** Opens [device]'s gallery at its newest image, or at [callId]'s; does nothing when it has none. */
    fun show(context: Context, device: Device, callId: String? = null) {
        val pages = pagesFor(device)
        if (pages.isEmpty()) return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
        val when_ = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val badges = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val counter = TextView(context).apply {
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        fun showCaption(position: Int) {
            val page = pages[position]
            when_.text = DateUtils.getRelativeDateTimeString(
                context, page.atMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0
            )
            CallResultBadges.fill(badges, page.event, 28, withNames = true)
            counter.text = if (pages.size > 1) "${position + 1} / ${pages.size}" else ""
        }

        val pager = RecyclerView(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = PageAdapter(pages, device.name)
        }
        PagerSnapHelper().attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val position = (recyclerView.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                if (position != RecyclerView.NO_POSITION) {
                    showCaption(position)
                    // The page left behind starts unzoomed when it comes back.
                    if (position > 0) recyclerView.adapter?.notifyItemChanged(position - 1)
                    if (position < pages.size - 1) recyclerView.adapter?.notifyItemChanged(position + 1)
                }
            }
        })
        val start = callId?.let { id -> pages.indexOfFirst { it.event?.callId == id }.takeIf { it >= 0 } } ?: 0
        pager.scrollToPosition(start)
        showCaption(start)

        val close = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = Texts.get("snapshot_close")
            setOnClickListener { dialog.dismiss() }
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(64), dp(12))
            setBackgroundColor(0x99000000.toInt())
            addView(TextView(context).apply {
                text = device.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(when_)
        }
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundColor(0x99000000.toInt())
            addView(badges, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(counter)
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private class PageAdapter(val pages: List<Page>, val name: String) : RecyclerView.Adapter<PageAdapter.Holder>() {
        class Holder(val image: ZoomableImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ZoomableImageView(parent.context).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = pages[position].file
            holder.image.reset()
            holder.image.contentDescription = name
            Theme.glidegeneric.load(file).signature(ObjectKey(file.lastModified())).into(holder.image)
        }

        override fun getItemCount() = pages.size
    }
}
