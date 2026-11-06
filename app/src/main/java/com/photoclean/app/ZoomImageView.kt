package com.photoclean.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * 두 손가락으로 벌리고 오므려 확대/축소, 더블탭으로 확대/원래대로,
 * 확대 상태에서는 한 손가락으로 이동.
 * 확대하지 않은 상태의 '밀기'는 [onSwipe] 로 넘겨 다음/이전/휴지통에 쓴다.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    /** dx, dy: 밀어낸 거리(px). 확대 중이 아닐 때만 호출된다. */
    var onSwipe: ((dx: Float, dy: Float) -> Unit)? = null

    /** 확대 상태가 바뀔 때 (true = 확대 중) */
    var onZoomChanged: ((Boolean) -> Unit)? = null

    /**
     * 사용자가 마지막으로 맞춘 확대 상태: 배율(화면 맞춤 대비)과
     * 화면 가운데에 보이던 지점(사진 안의 상대 위치 0~1).
     * 다음 사진이 들어오면 같은 배율·같은 위치로 확대해서 보여준다.
     */
    private var keptFactor = 1f
    private var keptCx = 0.5f
    private var keptCy = 0.5f

    private val m = Matrix()
    private val values = FloatArray(9)
    private var baseScale = 1f

    val isZoomed: Boolean get() = currentScale() > baseScale * 1.02f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val target = (currentScale() * d.scaleFactor).coerceIn(baseScale, baseScale * MAX_ZOOM)
                val f = target / currentScale()
                m.postScale(f, f, d.focusX, d.focusY)
                fixTranslation()
                imageMatrix = m
                remember()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) {
                fitToScreen()
            } else {
                val f = DOUBLE_TAP_ZOOM
                m.postScale(f, f, e.x, e.y)
                fixTranslation()
                imageMatrix = m
            }
            remember()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed || scaleDetector.isInProgress) return false
            m.postTranslate(-dx, -dy)
            fixTranslation()
            imageMatrix = m
            remember()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            if (isZoomed || multiTouch) return false
            onSwipe?.invoke(e2.x - start.x, e2.y - start.y)
            return true
        }
    })

    /** 이번 터치 동안 두 손가락 이상이 닿았는지 (핀치 후 손 뗄 때 밀기로 오인하지 않도록). */
    private var multiTouch = false

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        if (ev.pointerCount > 1) multiTouch = true
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    /** 새 사진/크기 변경 시: 화면 맞춤 후, 기억해 둔 확대 상태가 있으면 그대로 적용. */
    private fun resetZoom() {
        if (!fitToScreen()) return
        if (keptFactor > 1.02f) applyKept()
    }

    /** 기억한 배율로 확대하고, 기억한 지점이 화면 가운데 오도록 이동. */
    private fun applyKept() {
        val d = drawable ?: return
        val f = keptFactor
        m.postScale(f, f, width / 2f, height / 2f)
        val pt = floatArrayOf(keptCx * d.intrinsicWidth, keptCy * d.intrinsicHeight)
        m.mapPoints(pt)
        m.postTranslate(width / 2f - pt[0], height / 2f - pt[1])
        fixTranslation()
        imageMatrix = m
    }

    /** 지금 확대 상태를 기억 (다음 사진에 적용). */
    private fun remember() {
        val d = drawable ?: return
        val wasZoomed = keptFactor > 1.02f
        keptFactor = currentScale() / baseScale
        val inv = Matrix()
        if (m.invert(inv)) {
            val pt = floatArrayOf(width / 2f, height / 2f)
            inv.mapPoints(pt)
            keptCx = (pt[0] / d.intrinsicWidth).coerceIn(0f, 1f)
            keptCy = (pt[1] / d.intrinsicHeight).coerceIn(0f, 1f)
        }
        val nowZoomed = keptFactor > 1.02f
        if (wasZoomed != nowZoomed) onZoomChanged?.invoke(nowZoomed)
    }

    /** 확대 유지를 끄고 화면 맞춤으로. */
    fun clearZoom() {
        fitToScreen()
        remember()
    }

    /** 화면에 꽉 맞게(가운데 정렬). 사진이 없거나 크기를 모르면 false. */
    private fun fitToScreen(): Boolean {
        val d = drawable ?: return false
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return false
        baseScale = minOf(vw / dw, vh / dh)
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f)
        imageMatrix = m
        return true
    }

    private fun currentScale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** 사진이 화면 밖으로 빠지거나 빈 공간이 생기지 않게 위치 보정. */
    private fun fixTranslation() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dx = when {
            r.width() <= vw -> (vw - r.width()) / 2f - r.left
            r.left > 0 -> -r.left
            r.right < vw -> vw - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= vh -> (vh - r.height()) / 2f - r.top
            r.top > 0 -> -r.top
            r.bottom < vh -> vh - r.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
    }

    companion object {
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
