package com.simpleword.editor

import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Чтение и запись документов Word (.docx) — упрощённое, но совместимое с MS Word / LibreOffice / Google Docs. */
object DocxIO {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    // ======================= ЗАПИСЬ =======================

    fun write(doc: Spanned, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun entry(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", ROOT_RELS)
            entry("word/_rels/document.xml.rels", DOC_RELS)
            entry("word/styles.xml", STYLES)
            entry("word/document.xml", documentXml(doc))
        }
    }

    private fun documentXml(t: Spanned): String {
        val sb = StringBuilder(t.length * 2 + 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<w:document xmlns:w=\"$W\"><w:body>")
        val text = t.toString()
        var ps = 0
        while (true) {
            val nl = text.indexOf('\n', ps)
            val pe = if (nl < 0) text.length else nl
            sb.append("<w:p>")
            when (Fmts.alignAt(t, ps)) {
                Layout.Alignment.ALIGN_CENTER -> sb.append("<w:pPr><w:jc w:val=\"center\"/></w:pPr>")
                Layout.Alignment.ALIGN_OPPOSITE -> sb.append("<w:pPr><w:jc w:val=\"right\"/></w:pPr>")
                else -> {}
            }
            appendRuns(sb, t, text, ps, pe)
            sb.append("</w:p>")
            if (nl < 0) break
            ps = nl + 1
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        sb.append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>")
        sb.append("</w:sectPr></w:body></w:document>")
        return sb.toString()
    }

    private fun appendRuns(sb: StringBuilder, t: Spanned, text: String, ps: Int, pe: Int) {
        if (ps >= pe) return
        val bounds = sortedSetOf(ps, pe)
        for (sp in t.getSpans(ps, pe, Fmt::class.java)) {
            val a = t.getSpanStart(sp)
            val b = t.getSpanEnd(sp)
            if (a in (ps + 1) until pe) bounds.add(a)
            if (b in (ps + 1) until pe) bounds.add(b)
        }
        val pts = bounds.toList()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (a >= b) continue
            val fmt = HashMap<Kind, Any>()
            for (sp in t.getSpans(a, b, Fmt::class.java)) {
                if (t.getSpanStart(sp) <= a && t.getSpanEnd(sp) >= b) fmt[sp.kind] = sp.value
            }
            sb.append("<w:r>")
            if (fmt.isNotEmpty()) {
                // порядок элементов важен для строгих читателей (как в схеме OOXML)
                sb.append("<w:rPr>")
                if (fmt.containsKey(Kind.BOLD)) sb.append("<w:b/><w:bCs/>")
                if (fmt.containsKey(Kind.ITALIC)) sb.append("<w:i/><w:iCs/>")
                if (fmt.containsKey(Kind.STRIKE)) sb.append("<w:strike/>")
                (fmt[Kind.COLOR] as? Int)?.let { sb.append("<w:color w:val=\"${hex(it)}\"/>") }
                (fmt[Kind.SIZE] as? Float)?.let {
                    val hp = (it * 24).roundToInt().coerceIn(2, 3276)
                    sb.append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>")
                }
                if (fmt.containsKey(Kind.UNDERLINE)) sb.append("<w:u w:val=\"single\"/>")
                (fmt[Kind.HIGHLIGHT] as? Int)?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                sb.append("</w:rPr>")
            }
            // табуляции — отдельными элементами
            val chunk = text.substring(a, b)
            val parts = chunk.split('\t')
            parts.forEachIndexed { idx, part ->
                if (idx > 0) sb.append("<w:tab/>")
                if (part.isNotEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
            }
            sb.append("</w:r>")
        }
    }

    private fun hex(c: Int) = String.format(Locale.US, "%06X", c and 0xFFFFFF)

    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> b.append("&amp;")
                ch == '<' -> b.append("&lt;")
                ch == '>' -> b.append("&gt;")
                ch == '"' -> b.append("&quot;")
                ch.code < 0x20 -> {} // управляющие символы в XML запрещены
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    // ======================= ЧТЕНИЕ =======================

    private class RunProps {
        var b = false; var i = false; var u = false; var s = false
        var size: Float? = null; var color: Int? = null; var hl: Int? = null
    }

    private class Run(val start: Int, val end: Int, val p: RunProps)

    fun read(input: InputStream): SpannableStringBuilder {
        var xml: ByteArray? = null
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null && xml == null) {
                if (e.name == "word/document.xml") xml = z.readBytes()
                e = z.nextEntry
            }
        }
        val bytes = xml ?: throw IllegalArgumentException("Это не документ .docx")
        return parse(bytes)
    }

    private fun parse(bytes: ByteArray): SpannableStringBuilder {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")

        val sb = SpannableStringBuilder()
        val runs = ArrayList<Run>()
        val aligns = ArrayList<Triple<Int, Int, Layout.Alignment>>()
        var firstPara = true
        var paraStart = 0
        var paraAlign: Layout.Alignment? = null
        var inPPr = false
        var inRPr = false
        var cur = RunProps()

        fun addText(s: String) {
            if (s.isEmpty()) return
            val start = sb.length
            sb.append(s)
            runs.add(Run(start, sb.length, cur))
        }

        fun attr(name: String): String? = p.getAttributeValue(null, name)
        fun on(): Boolean = attr("w:val")?.lowercase(Locale.US) !in setOf("0", "false", "off", "none")

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:p" -> {
                        if (!firstPara) sb.append('\n')
                        firstPara = false
                        paraStart = sb.length
                        paraAlign = null
                    }
                    "w:pPr" -> inPPr = true
                    "w:jc" -> if (inPPr) paraAlign = when (attr("w:val")) {
                        "center" -> Layout.Alignment.ALIGN_CENTER
                        "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> null
                    }
                    "w:r" -> if (!inPPr) cur = RunProps()
                    "w:rPr" -> inRPr = true
                    "w:b" -> if (inRPr && !inPPr) cur.b = on()
                    "w:i" -> if (inRPr && !inPPr) cur.i = on()
                    "w:u" -> if (inRPr && !inPPr) cur.u = on()
                    "w:strike", "w:dstrike" -> if (inRPr && !inPPr) cur.s = on()
                    "w:sz" -> if (inRPr && !inPPr) cur.size = attr("w:val")?.toIntOrNull()?.let { it / 24f }
                    "w:color" -> if (inRPr && !inPPr) cur.color = parseHex(attr("w:val"))
                    "w:highlight" -> if (inRPr && !inPPr) cur.hl = HIGHLIGHTS[attr("w:val")]
                    "w:shd" -> if (inRPr && !inPPr) parseHex(attr("w:fill"))?.let { cur.hl = it }
                    "w:t" -> if (!inPPr) addText(p.nextText())
                    "w:tab" -> if (!inPPr) addText("\t")
                    "w:br", "w:cr" -> if (!inPPr) addText("\n")
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "w:pPr" -> inPPr = false
                    "w:rPr" -> inRPr = false
                    "w:p" -> paraAlign?.let { aligns.add(Triple(paraStart, sb.length, it)) }
                }
            }
            ev = p.next()
        }

        for (r in runs) {
            val a = r.start
            val b = r.end
            if (r.p.b) Fmts.apply(sb, a, b, Kind.BOLD, true)
            if (r.p.i) Fmts.apply(sb, a, b, Kind.ITALIC, true)
            if (r.p.u) Fmts.apply(sb, a, b, Kind.UNDERLINE, true)
            if (r.p.s) Fmts.apply(sb, a, b, Kind.STRIKE, true)
            r.p.size?.let { if (abs(it - 1f) > 0.01f) Fmts.apply(sb, a, b, Kind.SIZE, it) }
            r.p.color?.let { if (it != 0xFF000000.toInt()) Fmts.apply(sb, a, b, Kind.COLOR, it) }
            r.p.hl?.let { Fmts.apply(sb, a, b, Kind.HIGHLIGHT, it) }
        }
        for ((s, e, al) in aligns) {
            try {
                Fmts.setAlign(sb, s, maxOf(s, e), al)
            } catch (_: RuntimeException) {
            }
        }
        return sb
    }

    private fun parseHex(v: String?): Int? {
        if (v == null || v.equals("auto", true) || v.length != 6) return null
        return v.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    private val HIGHLIGHTS: Map<String?, Int> = mapOf(
        "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(), "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(),
        "darkBlue" to 0xFF000080.toInt(), "darkCyan" to 0xFF008080.toInt(), "darkGreen" to 0xFF008000.toInt(),
        "darkMagenta" to 0xFF800080.toInt(), "darkRed" to 0xFF800000.toInt(), "darkYellow" to 0xFF808000.toInt(),
        "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFC0C0C0.toInt(), "black" to 0xFF000000.toInt()
    )

    // ======================= Служебные части пакета =======================

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="24"/><w:szCs w:val="24"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""
}
