package com.simpleword.editor

/** История для «Отменить / Повторить». Хранит снимки документа (текст + форматирование). */
class History(private val max: Int = 100) {

    class Snap(val text: CharSequence, val selStart: Int, val selEnd: Int)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var current: Snap? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun reset(s: Snap) {
        undoStack.clear()
        redoStack.clear()
        current = s
    }

    fun push(s: Snap) {
        current?.let {
            undoStack.addLast(it)
            if (undoStack.size > max) undoStack.removeFirst()
        }
        current = s
        redoStack.clear()
    }

    fun undo(): Snap? {
        val prev = undoStack.removeLastOrNull() ?: return null
        current?.let { redoStack.addLast(it) }
        current = prev
        return prev
    }

    fun redo(): Snap? {
        val next = redoStack.removeLastOrNull() ?: return null
        current?.let { undoStack.addLast(it) }
        current = next
        return next
    }
}
