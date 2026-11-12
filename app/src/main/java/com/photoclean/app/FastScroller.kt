package com.photoclean.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 목록 오른쪽 빠른 스크롤 막대.
 * 스크롤하면 나타나고, 막대를 잡고 끌면 날짜 말풍선을 보여주며 빠르게 이동한다.
 * 막대 바깥 터치는 아래 목록으로 그대로 넘긴다.
 */
class FastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 목록 위치 → 말풍선 글자 (빈 문자열이면 말풍선 없음) */
    var labelFor: ((Int) -> String)? = null

    private var rv: RecyclerView? = null
    private val dp = resources.displayMetrics.density
    private val thumbW = 6 * dp
    private val thumbWActive = 10 * dp
    private val thumbH = 56 * dp
    private val touchW = 48 * dp
    private val edge = 6 * dp

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary_container)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15 * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    private var fraction = 0f
    private var dragging = false
    private var targetPos = 0
    private val hide = Runnable { if (!dragging) animate().alpha(0f).setDuration(250).start() }

    init {
        alpha = 0f
    }

    fun attach(recycler: RecyclerView) {
        rv = recycler
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dragging) return
                val range = r.computeVerticalScrollRange() - r.computeVerticalScrollExtent()
                fraction = if (range > 0) r.computeVerticalScrollOffset().toFloat() / range else 0f
                if (dy != 0) reveal()
                invalidate()
            }
        })
    }

    private fun scrollable(): Boolean {
        val r = rv ?: return false
        return r.computeVerticalScrollRange() > r.height * 3
    }

    private fun reveal() {
        if (!scrollable()) return
        animate().cancel()
        alpha = 1f
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    private fun track() = (height - paddingTop - paddingBottom - thumbH).coerceAtLeast(1f)

    override fun onDraw(canvas: Canvas) {
        if (!scrollable()) return
        val top = paddingTop + fraction * track()
        val w = if (dragging) thumbWActive else thumbW
        rect.set(width - edge - w, top, width - edge, top + thumbH)
        canvas.drawRoundRect(rect, w / 2, w / 2, thumbPaint)

        if (dragging) {
            val label = labelFor?.invoke(targetPos).orEmpty()
            if (label.isNotEmpty()) {
                val tw = textPaint.measureText(label)
                val bh = 44 * dp
                val bw = tw + 36 * dp
                val cy = (top + thumbH / 2).coerceIn(bh / 2, height - bh / 2)
                val right = width - edge - thumbWActive - 14 * dp
                rect.set(right - bw, cy - bh / 2, right, cy + bh / 2)
                canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
                val fm = textPaint.fontMetrics
                canvas.drawText(label, rect.left + 18 * dp, cy - (fm.ascent + fm.descent) / 2, textPaint)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (alpha < 0.1f || !scrollable() || ev.x < width - touchW) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                rv?.stopScroll()
                removeCallbacks(hide)
                alpha = 1f
                seek(ev.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { seek(ev.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                postDelayed(hide, 1200)
                invalidate()
                return true
            }
        }
        return dragging
    }

    private fun seek(y: Float) {
        val r = rv ?: return
        val count = r.adapter?.itemCount ?: 0
        if (count == 0) return
        fraction = ((y - paddingTop - thumbH / 2) / track()).coerceIn(0f, 1f)
        targetPos = (fraction * (count - 1)).roundToInt()
        (r.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetPos, 0)
        invalidate()
    }
}
