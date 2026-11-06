package com.photoclean.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** 전체 보기의 정사각형 칸: 썸네일 + 선택 표시. */
class GridCell(context: Context) : FrameLayout(context) {

    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF222222.toInt())
    }

    /** 선택 시 사진을 살짝 어둡게. */
    private val dim = View(context).apply {
        setBackgroundColor(0x66000000)
        visibility = GONE
    }

    private val dp = context.resources.displayMetrics.density

    private val check = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Color.WHITE)
        visibility = GONE
    }

    /** 아래쪽 작은 글씨 (휴지통: "n일 남음") */
    private val label = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        gravity = Gravity.CENTER
        visibility = GONE
    }

    fun setLabel(text: String?) {
        label.text = text ?: ""
        label.visibility = if (text == null) GONE else VISIBLE
    }

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val size = (24 * dp).toInt()
        addView(check, LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            val mgn = (6 * dp).toInt()
            setMargins(mgn, mgn, mgn, mgn)
        })
        label.setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** selectionMode 가 아니면 체크 표시를 숨긴다. */
    fun setSelectionState(selectionMode: Boolean, selected: Boolean) {
        check.visibility = if (selectionMode) VISIBLE else GONE
        dim.visibility = if (selectionMode && selected) VISIBLE else GONE
        check.text = if (selected) "✓" else ""
        check.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) setColor(0xFFE53935.toInt()) else setColor(0x33000000)
            setStroke((2 * dp).toInt(), Color.WHITE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
