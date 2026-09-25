package com.simpleword.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.EditText
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Поле ввода в виде листа бумаги.
 * Высота растёт автоматически по мере набора текста; в режиме «разметка страницы»
 * высота округляется до целого числа листов A4 и рисуются границы страниц.
 */
class PageEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    var onSelChanged: ((Int, Int) -> Unit)? = null

    var showPages: Boolean = true
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
                invalidate()
            }
        }

    private val pageRatio = 297f / 210f
    private val dp = resources.displayMetrics.density

    private val breakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8BCC2.toInt()
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * resources.displayMetrics.density, 6f * resources.displayMetrics.density), 0f)
    }
    private val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
    }

    private fun pageHeight(width: Int): Float = width * pageRatio

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (showPages && measuredWidth > 0) {
            val ph = pageHeight(measuredWidth)
            val pages = max(1, ceil(measuredHeight / ph).toInt())
            setMeasuredDimension(measuredWidth, (pages * ph).roundToInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showPages || width == 0) return
        val ph = pageHeight(width)
        val pages = max(1, (height / ph).roundToInt())
        for (i in 1..pages) {
            val y = i * ph
            if (i < pages) canvas.drawLine(0f, y, width.toFloat(), y, breakPaint)
            canvas.drawText("$i", width - 10 * dp, y - 8 * dp, numPaint)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelChanged?.invoke(selStart, selEnd)
    }

    /** Вставка всегда без чужого форматирования (как «Сохранить только текст» в Word). */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        return super.onTextContextMenuItem(id)
    }
}
