package com.one4zero.sshterm.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Renders a [TerminalEmulator] and handles touch, selection and keyboard input. */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Client {
        /** Bytes typed directly into the terminal (soft keyboard in direct mode / hardware keyboard). */
        fun onTerminalInput(data: ByteArray)
        fun onTerminalResize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
        fun onTerminalTap()
        fun onSelectionChanged(active: Boolean)
        fun onScrollPositionChanged(scrolledBack: Boolean)
        fun onFontSizeChanged(sizeSp: Float)
        /** Returns sticky modifiers from the extra keys row ([KeyEncoder.MOD_CTRL]...) and resets them. */
        fun consumeModifiers(): Int
        fun onPasteShortcut()
        /** A word in the output was double tapped. */
        fun onWordDoubleTapped(word: String)
    }

    var client: Client? = null

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            scrollOffset = 0
            lastScrollCounter = value?.let { synchronized(it) { it.scrollCounter } } ?: 0L
            clearSelection()
            lastReportedCols = -1
            lastReportedRows = -1
            updateTerminalSize()
            invalidate()
        }

    var colors: TerminalColors = TerminalColors.get(0)
        set(value) {
            field = value
            invalidate()
        }

    /** When true the soft keyboard types straight into the terminal. */
    var directInput = false
        set(value) {
            field = value
            isFocusableInTouchMode = value
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val fillPaint = Paint()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val charBuf = CharArray(512)

    private var cellWidth = 10f
    private var cellHeight = 20f
    private var baseline = 15f

    var fontSizeSp = 13f
        private set

    /** Number of lines scrolled back into history (0 = live view). */
    private var scrollOffset = 0
    private var lastScrollCounter = 0L
    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)

    private var lastReportedCols = -1
    private var lastReportedRows = -1

    // Selection, in absolute rows / columns (end column inclusive)
    var hasSelection = false
        private set
    private var selStartRow = 0L
    private var selStartCol = 0
    private var selEndRow = 0L
    private var selEndCol = 0

    /** 0 = none, 1 = start, 2 = end */
    private var dragHandle = 0
    private val handleRadius = dp(10f)
    private val touchSlopHandle = dp(28f)

    private var scaleFactorAccum = 1f

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        setFontSize(fontSizeSp)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---------------------------------------------------------------- metrics

    fun setFontSize(sp: Float) {
        val size = sp.coerceIn(6f, 40f)
        fontSizeSp = size
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
        cellWidth = textPaint.measureText("M").coerceAtLeast(1f)
        val fm = textPaint.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent).coerceAtLeast(1f)
        baseline = -fm.ascent
        updateTerminalSize()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTerminalSize()
    }

    private fun updateTerminalSize() {
        if (width <= 0 || height <= 0) return
        val cols = maxOf(TerminalEmulator.MIN_COLUMNS, (width / cellWidth).toInt())
        val rows = maxOf(TerminalEmulator.MIN_ROWS, (height / cellHeight).toInt())
        if (cols == lastReportedCols && rows == lastReportedRows) return
        if (emulator == null) return
        lastReportedCols = cols
        lastReportedRows = rows
        client?.onTerminalResize(cols, rows, width, height)
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val emu = emulator
        if (emu == null) {
            canvas.drawColor(colors.background)
            return
        }
        synchronized(emu) {
            val counter = emu.scrollCounter
            if (scrollOffset > 0 && counter != lastScrollCounter) {
                scrollOffset += (counter - lastScrollCounter).toInt()
            }
            lastScrollCounter = counter
            scrollOffset = scrollOffset.coerceIn(0, emu.scrollbackSize)

            val reverse = emu.reverseVideo
            val defaultBg = if (reverse) colors.foreground else colors.background
            canvas.drawColor(defaultBg)

            val rows = emu.rows
            for (vr in 0 until rows) {
                val index = vr - scrollOffset
                val row = emu.getRow(index) ?: continue
                try {
                    drawRow(canvas, emu, row, vr * cellHeight, emu.absoluteRow(index), reverse, defaultBg)
                } catch (e: RuntimeException) {
                    android.util.Log.e("TerminalView", "draw error", e)
                }
            }

            val cursorRow = emu.cursorY + scrollOffset
            if (emu.cursorVisible && cursorRow in 0 until rows) {
                drawCursor(canvas, emu, cursorRow, reverse)
            }
            if (hasSelection) drawHandles(canvas, emu)
        }
    }

    private fun resolveColor(color: Int, reverse: Boolean): Int {
        if (color and TextStyle.RGB_FLAG != 0) return (0xFF shl 24) or (color and 0xFFFFFF)
        return when (color) {
            TextStyle.COLOR_DEFAULT_FG -> if (reverse) colors.background else colors.foreground
            TextStyle.COLOR_DEFAULT_BG -> if (reverse) colors.foreground else colors.background
            else -> colors.palette[color and 0xFF]
        }
    }

    private fun cellColors(style: Long, reverse: Boolean, selected: Boolean, out: IntArray) {
        val flags = TextStyle.flags(style)
        var fgIndex = TextStyle.fg(style)
        if (flags and TextStyle.BOLD != 0L && fgIndex < 8) fgIndex += 8
        var fg = resolveColor(fgIndex, reverse)
        var bg = resolveColor(TextStyle.bg(style), reverse)
        if (flags and TextStyle.INVERSE != 0L) {
            val t = fg; fg = bg; bg = t
        }
        if (flags and TextStyle.DIM != 0L) fg = blend(fg, bg, 0.5f)
        if (selected) {
            bg = blend(SELECTION_COLOR, bg, 0.55f)
        }
        if (flags and TextStyle.INVISIBLE != 0L) fg = bg
        out[0] = fg
        out[1] = bg
    }

    private val colorTmp = IntArray(2)

    private fun drawRow(
        canvas: Canvas, emu: TerminalEmulator, row: TerminalRow, y: Float, absRow: Long,
        reverse: Boolean, defaultBg: Int,
    ) {
        val n = minOf(emu.columns, row.size)
        val text = row.text
        val styles = row.style
        var x = 0
        while (x < n) {
            val cp = text[x]
            if (cp == TerminalRow.WIDE_TAIL) {
                x++
                continue
            }
            val st = styles[x]
            val selected = hasSelection && isSelected(absRow, x)
            val wide = x + 1 < n && text[x + 1] == TerminalRow.WIDE_TAIL
            if (!wide && (cp == 0 || cp in 0x20..0x7E)) {
                var end = x + 1
                while (end < n && end - x < charBuf.size) {
                    val c = text[end]
                    if (styles[end] != st || !(c == 0 || c in 0x20..0x7E)) break
                    if (end + 1 < n && text[end + 1] == TerminalRow.WIDE_TAIL) break
                    if (hasSelection && isSelected(absRow, end) != selected) break
                    end++
                }
                drawRun(canvas, x, end, y, text, st, reverse, selected, defaultBg)
                x = end
            } else {
                val w = if (wide) 2 else 1
                cellColors(st, reverse, selected, colorTmp)
                val fg = colorTmp[0]
                val bg = colorTmp[1]
                val left = x * cellWidth
                if (bg != defaultBg) {
                    fillPaint.color = bg
                    canvas.drawRect(left, y, left + w * cellWidth, y + cellHeight, fillPaint)
                }
                if (cp > 0x20) drawGlyph(canvas, cp, left, y, w, fg, st)
                x += w
            }
        }
    }

    private fun drawRun(
        canvas: Canvas, start: Int, end: Int, y: Float, text: IntArray, style: Long,
        reverse: Boolean, selected: Boolean, defaultBg: Int,
    ) {
        cellColors(style, reverse, selected, colorTmp)
        val fg = colorTmp[0]
        val bg = colorTmp[1]
        val left = start * cellWidth
        val right = end * cellWidth
        if (bg != defaultBg) {
            fillPaint.color = bg
            canvas.drawRect(left, y, right, y + cellHeight, fillPaint)
        }
        var len = 0
        var visible = false
        for (i in start until end) {
            val c = text[i]
            val ch = if (c == 0) ' ' else c.toChar()
            if (ch != ' ') visible = true
            charBuf[len++] = ch
        }
        if (visible && fg != bg) {
            applyTextStyle(style, fg)
            canvas.drawText(charBuf, 0, len, left, y + baseline, textPaint)
        }
        decorate(canvas, style, fg, left, right, y)
    }

    private fun drawGlyph(canvas: Canvas, cp: Int, left: Float, y: Float, cells: Int, fg: Int, style: Long) {
        applyTextStyle(style, fg)
        val chars = Character.toChars(cp)
        val target = cells * cellWidth
        val measured = textPaint.measureText(chars, 0, chars.size)
        if (measured > target * 1.05f && measured > 0f) {
            textPaint.textScaleX = target / measured
            canvas.drawText(chars, 0, chars.size, left, y + baseline, textPaint)
            textPaint.textScaleX = 1f
        } else {
            canvas.drawText(chars, 0, chars.size, left + (target - measured) / 2f, y + baseline, textPaint)
        }
        decorate(canvas, style, fg, left, left + target, y)
    }

    private fun applyTextStyle(style: Long, fg: Int) {
        val flags = TextStyle.flags(style)
        textPaint.color = fg
        textPaint.isFakeBoldText = flags and TextStyle.BOLD != 0L
        textPaint.textSkewX = if (flags and TextStyle.ITALIC != 0L) -0.25f else 0f
    }

    private fun decorate(canvas: Canvas, style: Long, fg: Int, left: Float, right: Float, y: Float) {
        val flags = TextStyle.flags(style)
        if (flags and (TextStyle.UNDERLINE or TextStyle.STRIKETHROUGH) == 0L) return
        fillPaint.color = fg
        val thickness = maxOf(1f, cellHeight / 14f)
        if (flags and TextStyle.UNDERLINE != 0L) {
            val uy = y + cellHeight - thickness
            canvas.drawRect(left, uy, right, uy + thickness, fillPaint)
        }
        if (flags and TextStyle.STRIKETHROUGH != 0L) {
            val sy = y + cellHeight / 2f
            canvas.drawRect(left, sy, right, sy + thickness, fillPaint)
        }
    }

    private fun drawCursor(canvas: Canvas, emu: TerminalEmulator, viewRow: Int, reverse: Boolean) {
        val x = emu.cursorX
        val row = emu.getRow(emu.cursorY) ?: return
        val left = x * cellWidth
        val top = viewRow * cellHeight
        val wide = x + 1 < row.size && row.text[x + 1] == TerminalRow.WIDE_TAIL
        val right = left + (if (wide) 2 else 1) * cellWidth
        val cursorColor = if (reverse) colors.background else colors.cursor
        if (directInput && hasFocus()) {
            fillPaint.color = cursorColor
            canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
            val cp = if (x < row.size) row.text[x] else 0
            if (cp > 0x20) {
                val bg = if (reverse) colors.foreground else colors.background
                drawGlyph(canvas, cp, left, top, if (wide) 2 else 1, bg, row.style[x])
            }
        } else {
            fillPaint.color = cursorColor
            val t = maxOf(1f, dp(1.5f))
            canvas.drawRect(left, top, right, top + t, fillPaint)
            canvas.drawRect(left, top + cellHeight - t, right, top + cellHeight, fillPaint)
            canvas.drawRect(left, top, left + t, top + cellHeight, fillPaint)
            canvas.drawRect(right - t, top, right, top + cellHeight, fillPaint)
        }
    }

    private fun drawHandles(canvas: Canvas, emu: TerminalEmulator) {
        handlePaint.color = HANDLE_COLOR
        val (sr, sc, er, ec) = orderedSelection()
        val topAbs = emu.absoluteRow(-scrollOffset)
        val sy = (sr - topAbs).toFloat()
        val ey = (er - topAbs).toFloat()
        if (sy in 0f..emu.rows.toFloat()) {
            val cx = sc * cellWidth
            val cy = (sy + 1) * cellHeight + handleRadius
            canvas.drawCircle(cx, cy, handleRadius, handlePaint)
            canvas.drawRect(cx, cy - handleRadius, cx + handleRadius, cy, handlePaint)
        }
        if (ey in 0f..emu.rows.toFloat()) {
            val cx = (ec + 1) * cellWidth
            val cy = (ey + 1) * cellHeight + handleRadius
            canvas.drawCircle(cx, cy, handleRadius, handlePaint)
            canvas.drawRect(cx - handleRadius, cy - handleRadius, cx, cy, handlePaint)
        }
    }

    // ---------------------------------------------------------------- selection

    private data class Sel(val startRow: Long, val startCol: Int, val endRow: Long, val endCol: Int)

    private fun orderedSelection(): Sel =
        if (selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)) {
            Sel(selStartRow, selStartCol, selEndRow, selEndCol)
        } else {
            Sel(selEndRow, selEndCol, selStartRow, selStartCol)
        }

    private fun isSelected(absRow: Long, col: Int): Boolean {
        val s = orderedSelection()
        if (absRow < s.startRow || absRow > s.endRow) return false
        if (absRow == s.startRow && col < s.startCol) return false
        if (absRow == s.endRow && col > s.endCol) return false
        return true
    }

    fun clearSelection() {
        if (!hasSelection) return
        hasSelection = false
        dragHandle = 0
        client?.onSelectionChanged(false)
        invalidate()
    }

    fun selectAll() {
        val emu = emulator ?: return
        synchronized(emu) {
            selStartRow = emu.firstAbsoluteRow
            selStartCol = 0
            selEndRow = emu.lastAbsoluteRow
            selEndCol = emu.columns - 1
        }
        if (!hasSelection) {
            hasSelection = true
            client?.onSelectionChanged(true)
        }
        invalidate()
    }

    fun getSelectedText(): String {
        val emu = emulator ?: return ""
        if (!hasSelection) return ""
        val s = orderedSelection()
        return synchronized(emu) { emu.getText(s.startRow, s.startCol, s.endRow, s.endCol + 1) }
    }

    private fun cellAt(x: Float, y: Float, emu: TerminalEmulator): Pair<Long, Int> {
        val col = (x / cellWidth).toInt().coerceIn(0, emu.columns - 1)
        val vr = (y / cellHeight).toInt().coerceIn(0, emu.rows - 1)
        return Pair(emu.absoluteRow(vr - scrollOffset), col)
    }

    private fun isWordChar(cp: Int): Boolean =
        cp > 0 && (Character.isLetterOrDigit(cp) || "-_./~:@%+=?&#,$\\".indexOf(cp.toChar()) >= 0)

    /** Returns the word (path, file name, id...) under the given point. */
    private fun wordAt(x: Float, y: Float): String? {
        val emu = emulator ?: return null
        synchronized(emu) {
            val (r, c) = cellAt(x, y, emu)
            val row = emu.rowByAbsolute(r) ?: return null
            val limit = minOf(row.size, emu.columns)
            if (c >= limit || !isWordChar(row.text[c])) return null
            var start = c
            var end = c
            while (start > 0 && isWordChar(row.text[start - 1])) start--
            while (end + 1 < limit && isWordChar(row.text[end + 1])) end++
            return row.toText(start, end + 1).trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun startSelection(e: MotionEvent) {
        val emu = emulator ?: return
        synchronized(emu) {
            val (r, c) = cellAt(e.x, e.y, emu)
            val row = emu.rowByAbsolute(r) ?: return
            var start = c
            var end = c
            if (c < row.size && isWordChar(row.text[c])) {
                while (start > 0 && isWordChar(row.text[start - 1])) start--
                while (end + 1 < minOf(row.size, emu.columns) && isWordChar(row.text[end + 1])) end++
            }
            selStartRow = r
            selEndRow = r
            selStartCol = start
            selEndCol = end
        }
        hasSelection = true
        dragHandle = 2
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        client?.onSelectionChanged(true)
        invalidate()
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val emu = emulator ?: return 0
        val s = orderedSelection()
        // normalize so that start <= end
        selStartRow = s.startRow; selStartCol = s.startCol; selEndRow = s.endRow; selEndCol = s.endCol
        val topAbs = synchronized(emu) { emu.absoluteRow(-scrollOffset) }
        val sx = s.startCol * cellWidth
        val sy = (s.startRow - topAbs + 1) * cellHeight + handleRadius
        val ex = (s.endCol + 1) * cellWidth
        val ey = (s.endRow - topAbs + 1) * cellHeight + handleRadius
        val ds = hypot(x - sx, y - sy)
        val de = hypot(x - ex, y - ey)
        return when {
            de <= touchSlopHandle && de <= ds -> 2
            ds <= touchSlopHandle -> 1
            else -> 0
        }
    }

    private fun dragSelection(e: MotionEvent) {
        val emu = emulator ?: return
        // auto scroll while dragging near the edges
        if (e.y < cellHeight / 2) scrollBy(1) else if (e.y > height - cellHeight / 2) scrollBy(-1)
        synchronized(emu) {
            val (r, c) = cellAt(e.x, e.y - if (dragHandle != 0) cellHeight / 2 else 0f, emu)
            if (dragHandle == 1) {
                selStartRow = r; selStartCol = c
            } else {
                selEndRow = r; selEndCol = c
            }
        }
        invalidate()
    }

    // ---------------------------------------------------------------- scrolling

    val isScrolledBack: Boolean get() = scrollOffset > 0

    fun scrollToBottom() {
        scroller.forceFinished(true)
        if (scrollOffset != 0) {
            scrollOffset = 0
            client?.onScrollPositionChanged(false)
            invalidate()
        }
    }

    /** Positive = towards older lines. */
    private fun scrollBy(lines: Int) {
        val emu = emulator ?: return
        val max = synchronized(emu) { emu.scrollbackSize }
        val before = scrollOffset
        scrollOffset = (scrollOffset + lines).coerceIn(0, max)
        if (before != scrollOffset) {
            if ((before > 0) != (scrollOffset > 0)) client?.onScrollPositionChanged(scrollOffset > 0)
            invalidate()
        }
    }

    private fun onScrollGesture(distanceY: Float, e: MotionEvent) {
        val emu = emulator ?: return
        scrollRemainder += distanceY
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val alt: Boolean
        val tracking: Int
        val sgr: Boolean
        val appCursor: Boolean
        synchronized(emu) {
            alt = emu.isAltScreen
            tracking = emu.mouseTracking
            sgr = emu.mouseSgr
            appCursor = emu.applicationCursorKeys
        }
        if (alt) {
            // In full screen apps (less, vim, htop) a swipe scrolls the app.
            val up = lines < 0
            val sb = StringBuilder()
            val col = (e.x / cellWidth).toInt()
            val row = (e.y / cellHeight).toInt()
            repeat(minOf(abs(lines), 10)) {
                if (tracking != 0) sb.append(KeyEncoder.mouseWheel(up, col, row, sgr))
                else sb.append(KeyEncoder.encodeKey(if (up) TermKey.UP else TermKey.DOWN, 0, appCursor))
            }
            client?.onTerminalInput(sb.toString().toByteArray(Charsets.UTF_8))
        } else {
            scrollBy(-lines)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val emu = emulator ?: return
            val max = synchronized(emu) { emu.scrollbackSize }
            val newOffset = (scroller.currY / cellHeight).roundToInt().coerceIn(0, max)
            if (newOffset != scrollOffset) {
                val wasBack = scrollOffset > 0
                scrollOffset = newOffset
                if (wasBack != (scrollOffset > 0)) client?.onScrollPositionChanged(scrollOffset > 0)
            }
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- touch

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollRemainder = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (hasSelection) {
                clearSelection()
                return true
            }
            if (directInput) {
                requestFocus()
                showKeyboard()
            }
            client?.onTerminalTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (hasSelection) return false
            val word = wordAt(e.x, e.y) ?: return false
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            client?.onWordDoubleTapped(word)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            onScrollGesture(distanceY, e2)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val emu = emulator ?: return false
            val (alt, max) = synchronized(emu) { Pair(emu.isAltScreen, emu.scrollbackSize) }
            if (alt) return false
            scroller.fling(
                0, (scrollOffset * cellHeight).toInt(), 0, velocityY.toInt(),
                0, 0, 0, (max * cellHeight).toInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaleDetector.isInProgress) return
            startSelection(e)
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleFactorAccum = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactorAccum *= detector.scaleFactor
            if (abs(scaleFactorAccum - 1f) > 0.08f) {
                val newSize = (fontSizeSp * scaleFactorAccum).coerceIn(6f, 40f)
                scaleFactorAccum = 1f
                if (abs(newSize - fontSizeSp) >= 0.5f) {
                    setFontSize((newSize * 2).roundToInt() / 2f)
                    client?.onFontSizeChanged(fontSizeSp)
                }
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (emulator == null) return super.onTouchEvent(event)
        if (hasSelection) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (dragHandle == 0) {
                        dragHandle = hitHandle(event.x, event.y)
                    }
                    if (dragHandle != 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (dragHandle != 0) {
                    dragSelection(event)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragHandle != 0) {
                    dragHandle = 0
                    // reset the gesture detector (it may still think a long press is in progress)
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    gestureDetector.onTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            return true
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ---------------------------------------------------------------- keyboard

    fun showKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = directInput

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!directInput) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            flushEditable()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            flushEditable()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val editable = editable
            if (editable != null && editable.isNotEmpty()) {
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            repeat(maxOf(1, beforeLength)) { sendKey(TermKey.BACKSPACE, 0) }
            return true
        }

        private fun flushEditable() {
            val editable = editable ?: return
            val s = editable.toString()
            editable.clear()
            if (s.isNotEmpty()) sendTypedText(s)
        }
    }

    private fun sendTypedText(text: String) {
        val mods = client?.consumeModifiers() ?: 0
        val s = KeyEncoder.encodeText(text, mods and KeyEncoder.MOD_CTRL != 0, mods and KeyEncoder.MOD_ALT != 0)
        sendInput(s)
    }

    private fun sendKey(key: TermKey, modifiers: Int) {
        val emu = emulator ?: return
        val appCursor = synchronized(emu) { emu.applicationCursorKeys }
        sendInput(KeyEncoder.encodeKey(key, modifiers, appCursor))
    }

    private fun sendInput(s: String) {
        if (s.isEmpty()) return
        scrollToBottom()
        clearSelection()
        client?.onTerminalInput(s.toByteArray(Charsets.UTF_8))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.characters != null) {
            sendTypedText(event.characters)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (emulator == null) return false
        if (keyCode == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(keyCode)) return false
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.isCtrlPressed && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_V) {
            client?.onPasteShortcut()
            return true
        }
        var mods = 0
        if (event.isShiftPressed) mods = mods or KeyEncoder.MOD_SHIFT
        if (event.isAltPressed) mods = mods or KeyEncoder.MOD_ALT
        if (event.isCtrlPressed) mods = mods or KeyEncoder.MOD_CTRL
        val termKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> TermKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> TermKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> TermKey.END
            KeyEvent.KEYCODE_PAGE_UP -> TermKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> TermKey.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> TermKey.ENTER
            KeyEvent.KEYCODE_TAB -> TermKey.TAB
            KeyEvent.KEYCODE_DEL -> TermKey.BACKSPACE
            KeyEvent.KEYCODE_ESCAPE -> TermKey.ESCAPE
            KeyEvent.KEYCODE_F1 -> TermKey.F1
            KeyEvent.KEYCODE_F2 -> TermKey.F2
            KeyEvent.KEYCODE_F3 -> TermKey.F3
            KeyEvent.KEYCODE_F4 -> TermKey.F4
            KeyEvent.KEYCODE_F5 -> TermKey.F5
            KeyEvent.KEYCODE_F6 -> TermKey.F6
            KeyEvent.KEYCODE_F7 -> TermKey.F7
            KeyEvent.KEYCODE_F8 -> TermKey.F8
            KeyEvent.KEYCODE_F9 -> TermKey.F9
            KeyEvent.KEYCODE_F10 -> TermKey.F10
            KeyEvent.KEYCODE_F11 -> TermKey.F11
            KeyEvent.KEYCODE_F12 -> TermKey.F12
            else -> null
        }
        if (termKey != null) {
            mods = mods or (client?.consumeModifiers() ?: 0)
            sendKey(termKey, mods)
            return true
        }
        val metaWithoutCtrlAlt = event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val uc = event.getUnicodeChar(metaWithoutCtrlAlt)
        if (uc == 0) return false
        if (uc and KeyCharacterMap.COMBINING_ACCENT != 0) return true
        mods = mods or (client?.consumeModifiers() ?: 0)
        val s = KeyEncoder.encodeText(
            String(Character.toChars(uc)),
            mods and KeyEncoder.MOD_CTRL != 0,
            mods and KeyEncoder.MOD_ALT != 0,
        )
        sendInput(s)
        return true
    }

    /** Sends a special key (used by the extra keys row). */
    fun sendSpecialKey(key: TermKey, modifiers: Int) = sendKey(key, modifiers)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    companion object {
        private const val SELECTION_COLOR = 0xFF3D7BD9.toInt()
        private const val HANDLE_COLOR = 0xFF3D7BD9.toInt()

        private fun blend(a: Int, b: Int, ratio: Float): Int {
            val inv = 1f - ratio
            val r = ((a shr 16 and 0xFF) * ratio + (b shr 16 and 0xFF) * inv).toInt()
            val g = ((a shr 8 and 0xFF) * ratio + (b shr 8 and 0xFF) * inv).toInt()
            val bl = ((a and 0xFF) * ratio + (b and 0xFF) * inv).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
