package com.simpleword.editor

import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/** Виды символьного форматирования, которые поддерживает редактор. */
enum class Kind { BOLD, ITALIC, UNDERLINE, STRIKE, SIZE, COLOR, HIGHLIGHT }

/** Общий интерфейс «наших» span'ов — по нему мы отличаем их от служебных span'ов системы. */
interface Fmt {
    val kind: Kind
    val value: Any
}

class BoldSpan : StyleSpan(Typeface.BOLD), Fmt {
    override val kind get() = Kind.BOLD
    override val value: Any get() = true
}

class ItalicSpan : StyleSpan(Typeface.ITALIC), Fmt {
    override val kind get() = Kind.ITALIC
    override val value: Any get() = true
}

class ULineSpan : UnderlineSpan(), Fmt {
    override val kind get() = Kind.UNDERLINE
    override val value: Any get() = true
}

class StrikeSpan : StrikethroughSpan(), Fmt {
    override val kind get() = Kind.STRIKE
    override val value: Any get() = true
}

/** Размер шрифта хранится как множитель к базовому 12 pt (14 pt → 14/12). */
class SizeSpan(private val factor: Float) : RelativeSizeSpan(factor), Fmt {
    override val kind get() = Kind.SIZE
    override val value: Any get() = factor
}

class ColorSpan(private val color: Int) : ForegroundColorSpan(color), Fmt {
    override val kind get() = Kind.COLOR
    override val value: Any get() = color
}

class HighlightSpan(private val color: Int) : BackgroundColorSpan(color), Fmt {
    override val kind get() = Kind.HIGHLIGHT
    override val value: Any get() = color
}

/** Выравнивание абзаца. */
class AlignSpan(align: Layout.Alignment) : AlignmentSpan.Standard(align)

object Fmts {
    const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    const val BULLET = "• "

    fun create(kind: Kind, value: Any): Any = when (kind) {
        Kind.BOLD -> BoldSpan()
        Kind.ITALIC -> ItalicSpan()
        Kind.UNDERLINE -> ULineSpan()
        Kind.STRIKE -> StrikeSpan()
        Kind.SIZE -> SizeSpan(value as Float)
        Kind.COLOR -> ColorSpan(value as Int)
        Kind.HIGHLIGHT -> HighlightSpan(value as Int)
    }

    /**
     * Устанавливает (value != null) или снимает (value == null) формат [kind] на диапазоне [start, end).
     * Соседние и пересекающиеся span'ы с тем же значением склеиваются в один,
     * поэтому количество span'ов не растёт при наборе текста.
     */
    fun apply(t: Spannable, start: Int, end: Int, kind: Kind, value: Any?) {
        if (start >= end) return
        var ns = start
        var ne = end
        for (sp in t.getSpans(start, end, Fmt::class.java)) {
            if (sp.kind != kind) continue
            val s0 = t.getSpanStart(sp)
            val e0 = t.getSpanEnd(sp)
            if (s0 < 0) continue
            t.removeSpan(sp)
            if (value != null && sp.value == value) {
                ns = minOf(ns, s0)
                ne = maxOf(ne, e0)
            } else {
                if (s0 < start) t.setSpan(create(kind, sp.value), s0, start, EXCL)
                if (e0 > end) t.setSpan(create(kind, sp.value), end, e0, EXCL)
            }
        }
        if (value != null) t.setSpan(create(kind, value), ns, ne, EXCL)
    }

    /** Значение формата у символа в позиции [pos] (или null). */
    fun valueAt(t: Spanned, pos: Int, kind: Kind): Any? {
        if (pos < 0 || pos >= t.length) return null
        return t.getSpans(pos, pos + 1, Fmt::class.java).firstOrNull {
            it.kind == kind && t.getSpanStart(it) <= pos && t.getSpanEnd(it) > pos
        }?.value
    }

    /** true, если весь диапазон [s, e) покрыт форматом [kind]. */
    fun fullyCovered(t: Spanned, s: Int, e: Int, kind: Kind): Boolean {
        if (s >= e) return false
        val ranges = t.getSpans(s, e, Fmt::class.java)
            .filter { it.kind == kind }
            .map { t.getSpanStart(it) to t.getSpanEnd(it) }
            .sortedBy { it.first }
        var pos = s
        for ((a, b) in ranges) {
            if (a > pos) return false
            if (b > pos) pos = b
            if (pos >= e) return true
        }
        return pos >= e
    }

    // ---------- Абзацы ----------

    /** Абзацы, задетые диапазоном [s, e). Каждый — пара (начало, конец включая '\n'). */
    fun paragraphs(t: CharSequence, s: Int, e: Int): List<Pair<Int, Int>> {
        val list = ArrayList<Pair<Int, Int>>()
        val a = s.coerceIn(0, t.length)
        val b = e.coerceIn(a, t.length)
        val endLimit = if (b > a) b - 1 else b
        var ps = if (a <= 0) 0 else t.lastIndexOf('\n', a - 1) + 1
        while (true) {
            val nl = t.indexOf('\n', ps)
            val pe = if (nl < 0) t.length else nl + 1
            list.add(ps to pe)
            if (nl < 0 || pe > endLimit) break
            ps = pe
        }
        return list
    }

    fun alignAt(t: Spanned, pos: Int): Layout.Alignment? {
        val (ps, pe) = paragraphs(t, pos, pos).first()
        return t.getSpans(ps, pe, AlignSpan::class.java).filter {
            val a = t.getSpanStart(it)
            val b = t.getSpanEnd(it)
            if (ps < pe) a < pe && b > ps else a <= ps && b >= ps
        }.lastOrNull()?.alignment
    }

    fun norm(a: Layout.Alignment?): Layout.Alignment = a ?: Layout.Alignment.ALIGN_NORMAL

    /** Задаёт выравнивание всем абзацам, задетым диапазоном [s, e). */
    fun setAlign(t: Spannable, s: Int, e: Int, align: Layout.Alignment?) {
        for ((ps, pe) in paragraphs(t, s, e)) {
            for (sp in t.getSpans(ps, pe, AlignSpan::class.java)) {
                val a = t.getSpanStart(sp)
                val b = t.getSpanEnd(sp)
                val affects = if (ps < pe) a < pe && b > ps else a == ps && b == ps
                if (!affects) continue
                val old = sp.alignment
                t.removeSpan(sp)
                if (a < ps) t.setSpan(AlignSpan(old), a, ps, Spanned.SPAN_PARAGRAPH)
                if (b > pe) t.setSpan(AlignSpan(old), pe, b, Spanned.SPAN_PARAGRAPH)
            }
            t.setSpan(AlignSpan(norm(align)), ps, pe, Spanned.SPAN_PARAGRAPH)
        }
    }

    /** «Чистая» копия документа: текст + только наши span'ы (без служебных span'ов клавиатуры). */
    fun cleanCopy(t: CharSequence): SpannableStringBuilder {
        val b = SpannableStringBuilder(t.toString())
        if (t !is Spanned) return b
        for (sp in t.getSpans(0, t.length, Any::class.java)) {
            val s = t.getSpanStart(sp)
            val e = t.getSpanEnd(sp)
            if (s < 0) continue
            try {
                when (sp) {
                    is Fmt -> if (e > s) b.setSpan(create(sp.kind, sp.value), s, e, EXCL)
                    is AlignSpan -> b.setSpan(AlignSpan(sp.alignment), s, e, Spanned.SPAN_PARAGRAPH)
                }
            } catch (_: RuntimeException) {
                // некорректные границы абзаца — пропускаем этот span
            }
        }
        return b
    }
}
