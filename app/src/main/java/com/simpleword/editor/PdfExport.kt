package com.simpleword.editor

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/** Экспорт документа в PDF формата A4 (1 единица = 1 pt). */
object PdfExport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56f

    fun write(text: CharSequence, out: OutputStream) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            color = Color.BLACK
        }
        val width = (PAGE_W - 2 * MARGIN).toInt()
        val contentH = PAGE_H - 2 * MARGIN
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(true)
            .build()

        val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
        }

        val doc = PdfDocument()
        var line = 0
        var pageNo = 1
        do {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val c = page.canvas
            if (layout.lineCount > 0) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentH) end++
                if (end == line) end++ // слишком высокая строка — всё равно выводим
                val bottom = layout.getLineBottom(end - 1)
                c.save()
                c.translate(MARGIN, MARGIN - top)
                c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(c)
                c.restore()
                line = end
            }
            c.drawText("$pageNo", PAGE_W / 2f, PAGE_H - MARGIN / 2f, numPaint)
            doc.finishPage(page)
            pageNo++
        } while (line < layout.lineCount)

        doc.writeTo(out)
        doc.close()
    }
}
