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
package org.linhome.ui.ringcheck

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linhome.GenericActivity
import org.linhome.LinhomeApplication
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.entities.Action
import org.linhome.store.DeviceStore
import org.linhome.compatibility.Compatibility
import org.linhome.webhooks.Webhooks
import org.linphone.mediastream.Log

/**
 * "Check ongoing call": a door is ringing, but not on this phone (paused, or the door doesn't call it). Shows the
 * door's camera picture, refreshed every few seconds while it rings, "Open the door" (the site opens it and stops
 * every phone ringing: Adonis `ring_open`) and the door's buttons the site runs itself (a garage). There is no call:
 * nothing is sent to the door by DTMF, so DTMF-only buttons are not offered.
 *
 * It closes when the ring ends, after the door opened, or with Close; the Doors screen then shakes the calls
 * switch when the phone is paused, as a reminder (see [RESULT_INTERACTED]).
 */
class RingCheckActivity : GenericActivity() {

    companion object {
        private const val EXTRA_SITE = "site_id"
        private const val EXTRA_DOOR = "door_name"

        /** The picture is asked again this often while the door rings. */
        const val REFRESH_MS = 2_500L

        /** How long the outcome stays on screen before the view closes. */
        const val CLOSE_AFTER_MS = 2_000L

        /** The view's result: the person saw the ring (always, when it closes). */
        const val RESULT_INTERACTED = RESULT_FIRST_USER

        fun intent(context: Context, siteId: String?, doorName: String?): Intent =
            Intent(context, RingCheckActivity::class.java).putExtra(EXTRA_SITE, siteId).putExtra(EXTRA_DOOR, doorName)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var siteId: String? = null
    private var closing = false

    private lateinit var title: TextView
    private lateinit var picture: ImageView
    private lateinit var status: TextView
    private lateinit var openButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        siteId = intent.getStringExtra(EXTRA_SITE)
        setResult(RESULT_INTERACTED)
        setContentView(buildLayout(intent.getStringExtra(EXTRA_DOOR)))
    }

    override fun onResume() {
        super.onResume()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive && !closing) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refresh() {
        val ring = Webhooks.ring(siteId, snapshot = true) ?: return // no answer this time: try again
        if (closing) return
        if (!ring.ringing) {
            closeWith(Texts.get("ring_check_ended"))
            return
        }
        ring.door?.let { if (title.tag == null) title.text = Texts.get("ring_check_title", it) }
        ring.snapshot?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                picture.setImageBitmap(it)
                status.text = ""
            }
        } ?: run { if (picture.drawable == null) status.text = Texts.get("ring_check_no_picture") }
    }

    private fun openDoor() {
        if (closing) return
        Compatibility.vibrateActionFeedback()
        openButton.isEnabled = false
        status.text = Texts.get("ring_check_opening")
        scope.launch {
            val result = Webhooks.ringOpen(siteId)
            when {
                result == null -> {
                    status.text = Texts.get("ring_check_no_answer")
                    openButton.isEnabled = true
                }
                result.opened -> closeWith(Texts.get("ring_check_opened"))
                result.reason == "not_ringing" -> closeWith(Texts.get("ring_check_ended"))
                else -> {
                    Log.w("[RingCheck] The door did not open: ${result.reason} ${result.message}")
                    status.text = Texts.get("ring_check_failed")
                    openButton.isEnabled = true
                }
            }
        }
    }

    /** A button the site runs itself (`method: site`): reported as door_action, as during a call. */
    private fun siteAction(action: Action) {
        Compatibility.vibrateActionFeedback()
        LinhomeApplication.coreContext.callReporter.onDoorAction(
            null, action.type, action.code, early = true, sent = action.code != null, site = siteId
        )
        status.text = Texts.get("action_sent", labelOf(action))
    }

    private fun closeWith(message: String) {
        closing = true
        refreshJob?.cancel()
        status.text = message
        openButton.isEnabled = false
        scope.launch {
            delay(CLOSE_AFTER_MS)
            finish()
        }
    }

    /** The site's doors' buttons that the site runs, without duplicates. */
    private fun siteActions(): List<Action> =
        DeviceStore.devices
            .filter { Webhooks.siteForDoor(it.address)?.id == siteId }
            .flatMap { it.actions.orEmpty() }
            .filter { it.isSite() }
            .distinctBy { it.code }

    private fun labelOf(action: Action): String =
        action.label?.trim()?.ifEmpty { null } ?: Texts.get(action.typeName() ?: "") ?: action.code ?: ""

    private fun buildLayout(doorName: String?): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val accent = Theme.getColor("color_a")
        val onAccent = Theme.getColor("color_c")
        // The call screens' colors: light text on their dark gradient.
        val textColor = Theme.getColor("color_c")

        fun pill(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
            cornerRadius = 24f * density
            setColor(fill)
            stroke?.let { setStroke(dp(2), it) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // The call screens' header (the Adonis Open logo between two lines); the buttons below stay plain, as this
        // view can only open the door, not take the call.
        val header = org.linhome.databinding.ChunkCallTopBinding.inflate(layoutInflater, column, false)
        header.executePendingBindings()
        column.addView(header.root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        title = TextView(this).apply {
            text = doorName?.let { Texts.get("ring_check_title", it) } ?: Texts.get("ring_check_title_unknown")
            if (doorName != null) tag = "app" // the app's own name wins over the site's intercom name
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        column.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        picture = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0x1AFFFFFF) // a faint frame on the dark background until the picture comes
            minimumHeight = dp(200)
            contentDescription = Texts.get("ring_check_picture_desc")
        }
        column.addView(picture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        status = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            text = Texts.get("ring_check_loading")
        }
        column.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        openButton = Button(this).apply {
            text = Texts.get("ring_check_open")
            setTextColor(onAccent)
            background = pill(accent)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { openDoor() }
        }
        column.addView(openButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(20) })

        siteActions().forEach { action ->
            val button = Button(this).apply {
                text = labelOf(action)
                setTextColor(accent)
                background = pill(android.graphics.Color.TRANSPARENT, accent)
                isAllCaps = false
                setOnClickListener { siteAction(action) }
            }
            column.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        }

        val close = Button(this).apply {
            text = Texts.get("ring_check_close")
            setTextColor(textColor)
            background = null
            isAllCaps = false
            setOnClickListener { finish() }
        }
        column.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })

        return ScrollView(this).apply {
            background = Theme.getGradientColor("dark_light_vertical_gradient")
            isFillViewport = true
            fitsSystemWindows = true // the header below the status bar, as on the call screens
            addView(column)
        }
    }
}
