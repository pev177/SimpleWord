package com.simpleword.editor

import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_NAME = "Документ.docx"
        private const val AUTOSAVE = "autosave.docx"
        private val SIZES = intArrayOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 48, 72)
        private val WORD_RE = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*")
        private val TEXT_COLORS = longArrayOf(
            0xFF000000, 0xFF595959, 0xFF7F7F7F, 0xFFC00000, 0xFFFF0000, 0xFFFFC000,
            0xFF00B050, 0xFF92D050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0
        ).map { it.toInt() }.toIntArray()
        private val HIGHLIGHT_COLORS = longArrayOf(
            0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFFFF66FF, 0xFFFFC000, 0xFFC0C0C0
        ).map { it.toInt() }.toIntArray()
    }

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editor: PageEditText
    private lateinit var scroll: ZoomScrollView
    private lateinit var pageHolder: FrameLayout
    private lateinit var pageCard: View
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences

    // --- формат, которым будет набираться следующий текст (как в Word) ---
    private val typing = HashMap<Kind, Any>()
    private var typingAlign: Layout.Alignment? = null

    // --- служебные флаги для TextWatcher ---
    private var inChange = false
    private var ignoreChanges = false
    private var autoEditing = false
    private var changeStart = 0
    private var changeCount = 0

    // --- отмена / повтор ---
    private val history = History()
    private val handler = Handler(Looper.getMainLooper())
    private var recordPending = false
    private val recordRunnable = Runnable { recordHistory() }
    private val statsRunnable = Runnable { updateStatus() }

    // --- файл ---
    private var docUri: Uri? = null
    private var docName = DEFAULT_NAME
    private var dirty = false
    private var afterSave: (() -> Unit)? = null
    private var lastTitle = ""

    // --- масштаб ---
    private var zoom = 1f
    private var typingZoom = 0f          // 0 = подобрать автоматически
    private var autoZoom = true
    private var zoomedMode = false       // true = «режим ввода» (крупный текст), false = «разметка страницы»
    private var keyboardVisible = false
    private var dialogOpen = false
    private var zoomAnimator: ValueAnimator? = null
    private val modeRunnable = Runnable { if (autoZoom && !dialogOpen) setMode(keyboardVisible) }

    // --- кнопки панели ---
    private val toggleBtns = LinkedHashMap<Kind, TextView>()
    private val alignBtns = LinkedHashMap<Layout.Alignment, ImageView>()
    private lateinit var sizeBtn: TextView
    private lateinit var colorBtn: TextView
    private lateinit var bulletBtn: ImageView
    private lateinit var undoBtn: ImageView
    private lateinit var redoBtn: ImageView

    // --- выбор файлов (Storage Access Framework) ---
    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openUri(uri, true)
    }
    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(DocxIO.MIME)) { uri ->
        val action = afterSave
        afterSave = null
        if (uri != null) {
            persist(uri)
            if (writeDoc(uri)) {
                docUri = uri
                action?.invoke()
            }
        }
    }
    private val pdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) exportPdf(uri)
    }
    private val txtLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportTxt(uri)
    }

    // =====================================================================
    //  Жизненный цикл
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.editor)
        scroll = findViewById(R.id.scroll)
        pageHolder = findViewById(R.id.pageHolder)
        pageCard = findViewById(R.id.pageCard)
        status = findViewById(R.id.status)
        setSupportActionBar(toolbar)

        prefs = getSharedPreferences("simpleword", MODE_PRIVATE)
        autoZoom = prefs.getBoolean("autoZoom", true)
        typingZoom = prefs.getFloat("typingZoom", 0f)

        editor.setLineSpacing(0f, 1.15f)
        buildFormatBar()
        setupEditor()
        setupZoom()

        if (!restoreAutosave()) newDocument()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        autosave()
    }

    private fun handleViewIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) {
            i.data?.let { u -> confirmUnsaved { openUri(u, false) } }
        }
    }

    // =====================================================================
    //  Редактор: применение формата к набираемому тексту
    // =====================================================================

    private fun setupEditor() {
        editor.onSelChanged = { _, _ ->
            if (!inChange && !ignoreChanges && ::sizeBtn.isInitialized) updateTypingFromCursor()
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!ignoreChanges) inChange = true
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                changeStart = start
                changeCount = count
            }

            override fun afterTextChanged(e: Editable) {
                if (ignoreChanges) return
                val start = changeStart
                val count = changeCount
                if (count > 0) {
                    // Новому тексту — текущий формат набора
                    val end = min(e.length, start + count)
                    for (k in Kind.values()) Fmts.apply(e, start, end, k, typing[k])
                    val sel = editor.selectionStart
                    if (sel >= 0 && Fmts.norm(Fmts.alignAt(e, sel)) != Fmts.norm(typingAlign)) {
                        Fmts.setAlign(e, sel, sel, typingAlign)
                    }
                }
                inChange = false
                if (count == 0) updateTypingFromCursor()
                onDocChanged()
                if (!autoEditing && count == 1 && start < e.length && e[start] == '\n') continueBullet(e, start)
            }
        })
    }

    /** Enter в пункте списка — новый пункт; Enter в пустом пункте — выход из списка. */
    private fun continueBullet(e: Editable, nlPos: Int) {
        val prevStart = if (nlPos == 0) 0 else e.lastIndexOf('\n', nlPos - 1) + 1
        if (!e.startsWith(Fmts.BULLET, prevStart)) return
        autoEditing = true
        try {
            if (nlPos - prevStart == Fmts.BULLET.length) e.delete(prevStart, nlPos + 1)
            else e.insert(nlPos + 1, Fmts.BULLET)
        } finally {
            autoEditing = false
        }
    }

    private fun onDocChanged() {
        dirty = true
        updateTitle()
        recordPending = true
        handler.removeCallbacks(recordRunnable)
        handler.postDelayed(recordRunnable, 700)
        handler.removeCallbacks(statsRunnable)
        handler.postDelayed(statsRunnable, 300)
        refreshUndoRedo()
    }

    private fun sel(): Pair<Int, Int> {
        val a = max(0, editor.selectionStart)
        val b = max(0, editor.selectionEnd)
        return min(a, b) to max(a, b)
    }

    /** Формат набора берётся от символа перед курсором — так же, как в Word. */
    private fun updateTypingFromCursor() {
        val t = editor.text ?: return
        val (s, e) = sel()
        typing.clear()
        if (t.isNotEmpty()) {
            var pos = if (s != e) s else s - 1
            if (s == e && (pos < 0 || t[pos] == '\n') && s < t.length && t[s] != '\n') pos = s
            if (pos in 0 until t.length) {
                for (k in Kind.values()) Fmts.valueAt(t, pos, k)?.let { typing[k] = it }
            }
        }
        typingAlign = Fmts.alignAt(t, s)
        refreshToolbar()
    }

    private fun currentValue(kind: Kind): Any? {
        val t = editor.text ?: return null
        val (s, e) = sel()
        return if (s == e) typing[kind] else Fmts.valueAt(t, s, kind)
    }

    private fun setValue(kind: Kind, v: Any?) {
        val t = editor.text ?: return
        val (s, e) = sel()
        if (v == null) typing.remove(kind) else typing[kind] = v
        if (s != e) {
            Fmts.apply(t, s, e, kind, v)
            formatChanged()
        }
        refreshToolbar()
    }

    private fun toggleFormat(kind: Kind) {
        val t = editor.text ?: return
        val (s, e) = sel()
        val on = if (s == e) typing[kind] == null else !Fmts.fullyCovered(t, s, e, kind)
        setValue(kind, if (on) true else null)
    }

    private fun setAlignment(a: Layout.Alignment?) {
        val t = editor.text ?: return
        val (s, e) = sel()
        Fmts.setAlign(t, s, e, a)
        typingAlign = a
        formatChanged()
        refreshToolbar()
    }

    private fun toggleBullets() {
        val t = editor.text ?: return
        val (s, e) = sel()
        val paras = Fmts.paragraphs(t, s, e)
        val all = paras.all { t.startsWith(Fmts.BULLET, it.first) }
        autoEditing = true
        try {
            for ((ps, _) in paras.asReversed()) {
                if (all) t.delete(ps, ps + Fmts.BULLET.length)
                else if (!t.startsWith(Fmts.BULLET, ps)) t.insert(ps, Fmts.BULLET)
            }
        } finally {
            autoEditing = false
        }
        refreshToolbar()
    }

    private fun clearFormatting() {
        val t = editor.text ?: return
        val (s, e) = sel()
        typing.clear()
        if (s != e) {
            for (k in Kind.values()) Fmts.apply(t, s, e, k, null)
            formatChanged()
        } else {
            toast("Выделите текст — или просто продолжайте печатать без форматирования")
        }
        refreshToolbar()
    }

    private fun currentPt(): Int = (((currentValue(Kind.SIZE) as? Float) ?: 1f) * 12).roundToInt()

    private fun setSizePt(pt: Int) = setValue(Kind.SIZE, if (pt == 12) null else pt / 12f)

    private fun stepSize(dir: Int) {
        val cur = currentPt()
        val next = if (dir > 0) SIZES.firstOrNull { it > cur } ?: SIZES.last()
        else SIZES.lastOrNull { it < cur } ?: SIZES.first()
        setSizePt(next)
    }

    private fun formatChanged() {
        dirty = true
        updateTitle()
        recordHistory()
    }

    // =====================================================================
    //  Отмена / повтор
    // =====================================================================

    private fun snap() = History.Snap(
        Fmts.cleanCopy(editor.text ?: ""), max(0, editor.selectionStart), max(0, editor.selectionEnd)
    )

    private fun recordHistory() {
        handler.removeCallbacks(recordRunnable)
        recordPending = false
        history.push(snap())
        refreshUndoRedo()
    }

    private fun flushHistory() {
        if (recordPending) recordHistory()
    }

    private fun undo() {
        flushHistory()
        history.undo()?.let { restore(it) }
    }

    private fun redo() {
        flushHistory()
        history.redo()?.let { restore(it) }
    }

    private fun restore(s: History.Snap) {
        ignoreChanges = true
        editor.setText(Fmts.cleanCopy(s.text))
        val len = editor.length()
        editor.setSelection(s.selStart.coerceIn(0, len), s.selEnd.coerceIn(0, len))
        ignoreChanges = false
        inChange = false
        dirty = true
        updateTitle()
        updateTypingFromCursor()
        updateStatus()
        refreshUndoRedo()
    }

    private fun refreshUndoRedo() {
        if (!::undoBtn.isInitialized) return
        undoBtn.alpha = if (history.canUndo || recordPending) 1f else 0.35f
        redoBtn.alpha = if (history.canRedo && !recordPending) 1f else 0.35f
    }

    // =====================================================================
    //  Панель форматирования
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun ripple(): Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun styleButton(v: View, desc: String, onClick: () -> Unit) {
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            marginStart = dp(1)
            marginEnd = dp(1)
        }
        v.minimumWidth = dp(40)
        v.foreground = ripple()
        v.contentDescription = desc
        v.tooltipText = desc
        v.setOnClickListener { onClick() }
    }

    private fun textBtn(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(0xFF303030.toInt())
        setPadding(dp(9), 0, dp(9), 0)
        styleButton(this, desc, onClick)
    }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        styleButton(this, desc, onClick)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFCCCCCC.toInt())
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }

    private fun setActive(v: View, active: Boolean) {
        if (v.isSelected == active && (v.background != null) == active) return
        v.isSelected = active
        v.background = if (active) GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, R.color.active_btn))
        } else null
    }

    private fun buildFormatBar() {
        val row = findViewById<LinearLayout>(R.id.formatRow)

        undoBtn = iconBtn(R.drawable.ic_undo, "Отменить") { undo() }
        redoBtn = iconBtn(R.drawable.ic_redo, "Повторить") { redo() }
        row.addView(undoBtn)
        row.addView(redoBtn)
        row.addView(divider())

        sizeBtn = textBtn("12 ▾", "Размер шрифта") { showSizeDialog() }.apply { textSize = 15f }
        row.addView(sizeBtn)
        row.addView(textBtn("A⁺", "Увеличить шрифт") { stepSize(+1) }.apply { textSize = 16f })
        row.addView(textBtn("A⁻", "Уменьшить шрифт") { stepSize(-1) }.apply { textSize = 14f })
        row.addView(divider())

        fun toggle(label: String, kind: Kind, desc: String, look: TextView.() -> Unit) {
            val b = textBtn(label, desc) { toggleFormat(kind) }
            b.look()
            toggleBtns[kind] = b
            row.addView(b)
        }
        toggle("Ж", Kind.BOLD, "Полужирный") { setTypeface(typeface, Typeface.BOLD) }
        toggle("К", Kind.ITALIC, "Курсив") { setTypeface(Typeface.SERIF, Typeface.ITALIC) }
        toggle("Ч", Kind.UNDERLINE, "Подчёркнутый") { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG }
        toggle("abc", Kind.STRIKE, "Зачёркнутый") {
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            textSize = 15f
        }
        row.addView(divider())

        colorBtn = textBtn("A", "Цвет текста") { showColorDialog() }.apply {
            setTypeface(typeface, Typeface.BOLD)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }
        row.addView(colorBtn)
        val hlBtn = textBtn("ab", "Выделение цветом") { showHighlightDialog() }
        hlBtn.textSize = 15f
        hlBtn.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(0xFFFFF176.toInt())
        }
        (hlBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(6); bottomMargin = dp(6) }
        row.addView(hlBtn)
        row.addView(divider())

        alignBtns[Layout.Alignment.ALIGN_NORMAL] = iconBtn(R.drawable.ic_align_left, "По левому краю") { setAlignment(null) }
        alignBtns[Layout.Alignment.ALIGN_CENTER] = iconBtn(R.drawable.ic_align_center, "По центру") { setAlignment(Layout.Alignment.ALIGN_CENTER) }
        alignBtns[Layout.Alignment.ALIGN_OPPOSITE] = iconBtn(R.drawable.ic_align_right, "По правому краю") { setAlignment(Layout.Alignment.ALIGN_OPPOSITE) }
        alignBtns.values.forEach { row.addView(it) }
        bulletBtn = iconBtn(R.drawable.ic_bullets, "Маркированный список") { toggleBullets() }
        row.addView(bulletBtn)
    }

    private fun refreshToolbar() {
        if (!::sizeBtn.isInitialized) return
        val t = editor.text ?: return
        val (s, e) = sel()
        for ((k, b) in toggleBtns) setActive(b, if (s == e) typing[k] != null else Fmts.fullyCovered(t, s, e, k))
        sizeBtn.text = "${currentPt()} ▾"
        colorBtn.setTextColor((currentValue(Kind.COLOR) as? Int) ?: 0xFF303030.toInt())
        val a = Fmts.norm(typingAlign)
        for ((al, b) in alignBtns) setActive(b, al == a)
        val (ps, _) = Fmts.paragraphs(t, s, s).first()
        setActive(bulletBtn, t.startsWith(Fmts.BULLET, ps))
    }

    // =====================================================================
    //  Диалоги
    // =====================================================================

    private fun showDialog(b: MaterialAlertDialogBuilder): AlertDialog {
        val d = b.create()
        dialogOpen = true
        d.setOnDismissListener { dialogOpen = false }
        d.show()
        return d
    }

    private fun showSizeDialog() {
        val labels = SIZES.map { "$it pt" }.toTypedArray()
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle("Размер шрифта")
                .setSingleChoiceItems(labels, SIZES.indexOf(currentPt())) { d, which ->
                    setSizePt(SIZES[which])
                    d.dismiss()
                }
                .setNegativeButton("Отмена", null)
        )
    }

    private fun showColorDialog() = showPalette("Цвет текста", TEXT_COLORS, "Авто (чёрный)") { c ->
        setValue(Kind.COLOR, if (c == null || c == 0xFF000000.toInt()) null else c)
    }

    private fun showHighlightDialog() = showPalette("Выделение цветом", HIGHLIGHT_COLORS, "Без выделения") { c ->
        setValue(Kind.HIGHLIGHT, c)
    }

    private fun showPalette(title: String, colors: IntArray, noneLabel: String, onPick: (Int?) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = 6
            setPadding(dp(20), dp(16), dp(20), 0)
        }
        var dialog: AlertDialog? = null
        for (c in colors) {
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1), 0x33000000)
                }
                setOnClickListener {
                    onPick(c)
                    dialog?.dismiss()
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = dp(36)
                height = dp(36)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(v, lp)
        }
        dialog = showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(grid)
                .setNeutralButton(noneLabel) { _, _ -> onPick(null) }
                .setNegativeButton("Отмена", null)
        )
    }

    // =====================================================================
    //  Масштаб: «разметка страницы» ↔ «режим ввода» с увеличенным текстом
    // =====================================================================

    private fun setupZoom() {
        // Следим за клавиатурой: появилась — увеличиваем текст, спряталась — показываем лист целиком
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val compat = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            val r = Rect()
            root.getWindowVisibleDisplayFrame(r)
            val screenH = root.rootView.height
            val heuristic = screenH > 0 && screenH - r.bottom > screenH * 0.15f
            val vis = compat || heuristic
            if (vis != keyboardVisible) {
                keyboardVisible = vis
                handler.removeCallbacks(modeRunnable)
                handler.postDelayed(modeRunnable, 120)
            }
        }
        scroll.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                scroll.post {
                    applyZoom(zoom)
                    if (editor.hasFocus()) scrollToCursorLater()
                }
            }
        }
        scroll.onScale = { f -> pinch(f) }
        scroll.onScaleEnd = { pinchEnd() }
    }

    private fun pageWidth(): Int {
        val w = pageCard.width
        return if (w > 0) w else scroll.width - pageHolder.paddingLeft - pageHolder.paddingRight
    }

    /** Масштаб, при котором основной текст (12 pt) выглядит на экране примерно как 18sp. */
    private fun autoTypingZoom(): Float {
        val pw = pageWidth()
        if (pw <= 0) return 2f
        val base = pw * 12f / 595f
        val target = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        return (target / base).coerceIn(1f, 6f)
    }

    private fun effectiveTypingZoom() = if (typingZoom > 0f) typingZoom else autoTypingZoom()

    /**
     * Лист имеет пропорции A4. При zoom = 1 шрифт и поля соответствуют печатной странице
     * (как «Разметка страницы» в Word). В режиме ввода текст крупнее и переносится по ширине экрана.
     */
    private fun applyZoom(z: Float) {
        zoom = z
        val pw = pageWidth()
        if (pw <= 0) return
        val base = pw * 12f / 595f
        editor.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * z)
        val m = if (zoomedMode) dp(14) else (pw * 56f / 595f).roundToInt()
        editor.setPadding(m, m, m, m)
        editor.showPages = !zoomedMode
        editor.minHeight = if (zoomedMode) max(0, scroll.height - pageHolder.paddingTop - pageHolder.paddingBottom) else 0
        handler.removeCallbacks(statsRunnable)
        handler.postDelayed(statsRunnable, 100)
    }

    private fun setMode(zoomed: Boolean) {
        val target = if (zoomed) effectiveTypingZoom() else 1f
        if (zoomed == zoomedMode && abs(zoom - target) < 0.01f) return
        zoomedMode = zoomed
        animateZoom(target)
    }

    private fun animateZoom(target: Float) {
        zoomAnimator?.cancel()
        val from = zoom
        if (abs(from - target) < 0.01f || (editor.text?.length ?: 0) > 20000) {
            applyZoom(target)
            scrollToCursorLater()
            return
        }
        zoomAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyZoom(it.animatedValue as Float) }
            doOnEnd { scrollToCursorLater() }
            start()
        }
    }

    private fun pinch(f: Float) {
        zoomAnimator?.cancel()
        val nz = (zoom * f).coerceIn(1f, 6f)
        if (!zoomedMode && nz > 1.03f) zoomedMode = true
        if (abs(nz - zoom) > 0.005f) applyZoom(nz)
    }

    private fun pinchEnd() {
        if (zoom < 1.08f) {
            zoomedMode = false
            applyZoom(1f)
        } else {
            typingZoom = zoom
            prefs.edit().putFloat("typingZoom", typingZoom).apply()
        }
        if (editor.hasFocus()) scrollToCursorLater()
    }

    private fun scrollToCursorLater() {
        editor.requestLayout()
        editor.doOnPreDraw { scrollToCursor() }
    }

    /** Прокручивает так, чтобы строка с курсором была в верхней трети видимой области. */
    private fun scrollToCursor() {
        val l = editor.layout ?: return
        val off = max(0, editor.selectionEnd)
        val line = l.getLineForOffset(off)
        val y = pageCard.top + editor.top + editor.totalPaddingTop + l.getLineTop(line)
        scroll.smoothScrollTo(0, max(0, y - scroll.height / 3))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    // =====================================================================
    //  Меню
    // =====================================================================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_auto_zoom)?.isChecked = autoZoom
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new -> confirmUnsaved { newDocument() }
            R.id.action_open -> confirmUnsaved {
                openLauncher.launch(arrayOf(DocxIO.MIME, "text/plain", "application/octet-stream"))
            }
            R.id.action_save -> save()
            R.id.action_save_as -> saveAs()
            R.id.action_export_pdf -> pdfLauncher.launch(baseName() + ".pdf")
            R.id.action_export_txt -> txtLauncher.launch(baseName() + ".txt")
            R.id.action_send_whatsapp -> sendText(listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp")
            R.id.action_send_telegram -> sendText(listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"), "Telegram")
            R.id.action_send_other -> sendText(emptyList(), null)
            R.id.action_clear_format -> clearFormatting()
            R.id.action_fit_page -> {
                hideKeyboard()
                setMode(false)
            }
            R.id.action_reset_zoom -> {
                typingZoom = 0f
                prefs.edit().remove("typingZoom").apply()
                if (zoomedMode) animateZoom(effectiveTypingZoom())
            }
            R.id.action_auto_zoom -> {
                autoZoom = !autoZoom
                item.isChecked = autoZoom
                prefs.edit().putBoolean("autoZoom", autoZoom).apply()
                if (autoZoom) setMode(keyboardVisible)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // =====================================================================
    //  Отправка текста в мессенджеры
    // =====================================================================

    /**
     * Отправляет выделенный фрагмент, а если ничего не выделено — весь документ.
     * [packages] — пакеты мессенджера по порядку (обычная и бизнес/веб-версии);
     * пустой список — системное окно «Поделиться».
     */
    private fun sendText(packages: List<String>, appName: String?) {
        val t = editor.text?.toString() ?: ""
        val (s, e) = sel()
        val text = (if (s != e) t.substring(s, e) else t).trim()
        if (text.isEmpty()) {
            toast("Нечего отправлять — документ пуст")
            return
        }
        fun baseIntent() = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, baseName())
        }
        for (pkg in packages) {
            try {
                startActivity(baseIntent().setPackage(pkg))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // этот вариант приложения не установлен — пробуем следующий
            }
        }
        if (appName != null) toast("$appName не установлен — выберите приложение")
        startActivity(Intent.createChooser(baseIntent(), "Отправить текст"))
    }

    // =====================================================================
    //  Файлы
    // =====================================================================

    private fun baseName(): String = docName.substringBeforeLast('.').ifBlank { "Документ" }

    private fun newDocument() {
        docUri = null
        docName = DEFAULT_NAME
        typing.clear()
        typingAlign = null
        loadDoc(SpannableStringBuilder(), false)
    }

    private fun loadDoc(doc: CharSequence, isDirty: Boolean) {
        ignoreChanges = true
        editor.setText(doc)
        editor.setSelection(0)
        ignoreChanges = false
        inChange = false
        dirty = isDirty
        handler.removeCallbacks(recordRunnable)
        recordPending = false
        history.reset(snap())
        updateTypingFromCursor()
        updateTitle()
        updateStatus()
        refreshUndoRedo()
        scroll.scrollTo(0, 0)
    }

    private fun openUri(uri: Uri, persistPermission: Boolean) {
        try {
            val name = displayName(uri) ?: "Документ"
            val isTxt = name.endsWith(".txt", true) || contentResolver.getType(uri) == "text/plain"
            val doc: CharSequence = contentResolver.openInputStream(uri)?.use { inp ->
                if (isTxt) {
                    SpannableStringBuilder(
                        inp.readBytes().toString(Charsets.UTF_8)
                            .removePrefix("﻿")
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                    )
                } else DocxIO.read(inp)
            } ?: throw IOException("нет доступа к файлу")
            if (persistPermission) persist(uri)
            docUri = uri
            docName = name
            loadDoc(doc, false)
        } catch (e: Exception) {
            toast("Не удалось открыть файл: ${e.message}")
        }
    }

    private fun save() {
        val u = docUri
        if (u == null || !writeDoc(u)) saveAs()
    }

    private fun saveAs() {
        saveAsLauncher.launch(baseName() + ".docx")
    }

    private fun writeDoc(uri: Uri): Boolean = try {
        val name = displayName(uri) ?: docName
        val isTxt = name.endsWith(".txt", true)
        val out = (try {
            contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        }) ?: contentResolver.openOutputStream(uri, "w") ?: throw IOException("нет доступа к файлу")
        out.use { o ->
            if (isTxt) o.write((editor.text?.toString() ?: "").toByteArray(Charsets.UTF_8))
            else DocxIO.write(Fmts.cleanCopy(editor.text ?: ""), o)
        }
        docName = name
        dirty = false
        updateTitle()
        toast(if (isTxt) "Сохранено: $name (TXT хранит только текст)" else "Сохранено: $name")
        true
    } catch (e: Exception) {
        toast("Ошибка сохранения: ${e.message}")
        false
    }

    private fun exportPdf(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { PdfExport.write(Fmts.cleanCopy(editor.text ?: ""), it) }
            toast("PDF сохранён")
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun exportTxt(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write((editor.text?.toString() ?: "").toByteArray(Charsets.UTF_8)) }
            toast("TXT сохранён")
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun confirmUnsaved(action: () -> Unit) {
        if (!dirty || (editor.text.isNullOrEmpty() && docUri == null)) {
            action()
            return
        }
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle("Сохранить изменения?")
                .setMessage("В документе «$docName» есть несохранённые изменения.")
                .setPositiveButton("Сохранить") { _, _ ->
                    val u = docUri
                    if (u != null && writeDoc(u)) action()
                    else {
                        afterSave = action
                        saveAs()
                    }
                }
                .setNegativeButton("Не сохранять") { _, _ -> action() }
                .setNeutralButton("Отмена", null)
        )
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun autosave() {
        try {
            File(filesDir, AUTOSAVE).outputStream().use { DocxIO.write(Fmts.cleanCopy(editor.text ?: ""), it) }
            prefs.edit()
                .putString("uri", docUri?.toString())
                .putString("name", docName)
                .putBoolean("dirty", dirty)
                .putInt("sel", max(0, editor.selectionStart))
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreAutosave(): Boolean {
        val f = File(filesDir, AUTOSAVE)
        if (!f.exists()) return false
        return try {
            val doc = f.inputStream().use { DocxIO.read(it) }
            docUri = prefs.getString("uri", null)?.let { Uri.parse(it) }
            docName = prefs.getString("name", DEFAULT_NAME) ?: DEFAULT_NAME
            loadDoc(doc, prefs.getBoolean("dirty", false))
            editor.setSelection(prefs.getInt("sel", 0).coerceIn(0, editor.length()))
            true
        } catch (_: Exception) {
            false
        }
    }

    // =====================================================================
    //  Строка состояния и заголовок
    // =====================================================================

    private fun updateTitle() {
        val t = if (dirty) "$docName •" else docName
        if (t == lastTitle) return
        lastTitle = t
        toolbar.title = docName
        toolbar.subtitle = if (dirty) "не сохранено" else null
    }

    private fun updateStatus() {
        val t = editor.text?.toString() ?: ""
        val words = WORD_RE.findAll(t).count()
        val pages = if (!zoomedMode && editor.width > 0) {
            val ph = editor.width * 297f / 210f
            " · Стр.: ${max(1, (editor.height / ph).roundToInt())}"
        } else ""
        val mode = if (zoomedMode) "Ввод" else "Страница"
        status.text = "Слов: $words · Знаков: ${t.length}$pages · $mode ${(zoom * 100).roundToInt()}%"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
