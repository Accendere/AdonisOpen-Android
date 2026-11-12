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
package org.linhome.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An image that fits the view and can be pinched to zoom (up to [MAX_SCALE]), dragged while zoomed, and
 * double-tapped to zoom in or back out. The image never leaves the view's edges.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f
    }

    private val base = Matrix()
    private val zoom = Matrix()
    private val shown = Matrix()

    /** The zoom on top of the fitted image: 1 = fitted. */
    private var scale = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale <= 1f) return false
            zoom.postTranslate(-distanceX, -distanceY)
            apply()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1f) reset() else zoomBy(DOUBLE_TAP_SCALE, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // While zoomed, keep the parent (a pager, a list) from taking the drag.
        parent?.requestDisallowInterceptTouchEvent(scale > 1f || event.pointerCount > 1)
        return true
    }

    fun reset() {
        zoom.reset()
        scale = 1f
        apply()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        base.setRectToRect(
            RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER
        )
        reset()
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val target = (scale * factor).coerceIn(1f, MAX_SCALE)
        val applied = target / scale
        scale = target
        zoom.postScale(applied, applied, focusX, focusY)
        apply()
    }

    /** Shows base × zoom, pulled back so the image covers the view where it can, and is centred where it can't. */
    private fun apply() {
        val d = drawable
        if (d != null) {
            shown.set(base)
            shown.postConcat(zoom)
            val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            shown.mapRect(r)
            val dx = when {
                r.width() <= width -> (width - r.width()) / 2f - r.left
                r.left > 0 -> -r.left
                r.right < width -> width - r.right
                else -> 0f
            }
            val dy = when {
                r.height() <= height -> (height - r.height()) / 2f - r.top
                r.top > 0 -> -r.top
                r.bottom < height -> height - r.bottom
                else -> 0f
            }
            if (dx != 0f || dy != 0f) {
                zoom.postTranslate(dx, dy)
                shown.postTranslate(dx, dy)
            }
            imageMatrix = shown
        }
        invalidate()
    }
}
