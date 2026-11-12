package com.photoclean.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 전체 보기/휴지통의 정사각형 칸: 썸네일 + 선택 표시 + 하트 + 아래 글씨. */
class GridCell(context: Context) : FrameLayout(context) {

    private val dp = context.resources.displayMetrics.density

    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /** 선택하면 크림색으로 살짝 덮기 */
    private val dim = View(context).apply {
        setBackgroundColor(0x66FFF4DF)
        visibility = GONE
    }

    private val check = ImageView(context).apply {
        val pad = (3 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.on_primary))
        visibility = GONE
    }

    private val heart = ImageView(context).apply {
        setImageResource(R.drawable.ic_heart)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.heart))
        elevation = 2 * dp
        visibility = GONE
    }

    /** 아래쪽 작은 글씨 (휴지통: "n일 남음") */
    private val label = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC8F603A.toInt())
        gravity = Gravity.CENTER
        setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
        visibility = GONE
    }

    init {
        // 둥근 모서리 칸 (안의 사진도 같이 잘림)
        setBackgroundResource(R.drawable.cell_bg)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val c = (22 * dp).toInt()
        addView(check, LayoutParams(c, c, Gravity.TOP or Gravity.END).apply {
            val m = (6 * dp).toInt()
            setMargins(m, m, m, m)
        })
        val h = (16 * dp).toInt()
        addView(heart, LayoutParams(h, h, Gravity.BOTTOM or Gravity.START).apply {
            val m = (6 * dp).toInt()
            setMargins(m, m, m, m)
        })
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** selectionMode 가 아니면 체크 표시를 숨긴다. 선택된 칸은 사진이 살짝 작아진다. */
    fun setSelectionState(selectionMode: Boolean, selected: Boolean) {
        check.visibility = if (selectionMode) VISIBLE else GONE
        val on = selectionMode && selected
        dim.visibility = if (on) VISIBLE else GONE
        check.elevation = 2 * dp
        if (on) {
            check.setBackgroundResource(R.drawable.cell_check_on)
            check.setImageResource(R.drawable.ic_check)
        } else {
            check.setBackgroundResource(R.drawable.cell_check_off)
            check.setImageDrawable(null)
        }
        val s = if (on) 0.9f else 1f
        image.scaleX = s
        image.scaleY = s
    }

    fun setFavorite(fav: Boolean) {
        heart.visibility = if (fav) VISIBLE else GONE
    }

    fun setLabel(text: String?) {
        label.text = text ?: ""
        label.visibility = if (text == null) GONE else VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
