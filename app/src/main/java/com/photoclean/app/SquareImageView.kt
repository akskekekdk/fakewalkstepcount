package com.photoclean.app

import android.content.Context
import android.widget.ImageView

/** 그리드에서 정사각형 칸을 만들기 위한 ImageView. */
class SquareImageView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
