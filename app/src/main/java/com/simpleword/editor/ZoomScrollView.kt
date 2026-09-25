package com.simpleword.editor

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ScrollView

/** ScrollView, который дополнительно распознаёт жест «щипок» для масштабирования. */
class ZoomScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var onScale: ((Float) -> Unit)? = null
    var onScaleEnd: (() -> Unit)? = null

    private var scaling = false
    private var childCancelled = false

    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onScale?.invoke(detector.scaleFactor)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            onScaleEnd?.invoke()
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) childCancelled = false

        if (ev.pointerCount > 1 || scaling || childCancelled) {
            // Два пальца — это масштаб: отменяем прокрутку/выделение у дочерних view
            if (!childCancelled) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                childCancelled = true
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) childCancelled = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
