package com.one4zero.sshterm.terminal

/**
 * A VT100/xterm compatible terminal emulator (subset sufficient for shells, editors,
 * pagers and ncurses applications). Not thread safe: callers synchronize on the instance.
 *
 * Rows are addressed in two ways:
 *  - screen rows: 0 until [rows]
 *  - absolute rows: stable indexes over scrollback + screen, see [absoluteRow].
 */
class TerminalEmulator(
    columns: Int,
    rows: Int,
    private val maxScrollback: Int,
    /** Receives responses which must be sent back to the remote side (DSR, DA...). */
    private val output: (ByteArray) -> Unit,
) {
    var columns = maxOf(columns, MIN_COLUMNS)
        private set
    var rows = maxOf(rows, MIN_ROWS)
        private set

    private var mainScreen = Array(this.rows) { TerminalRow(this.columns) }
    private var altScreen = Array(this.rows) { TerminalRow(this.columns) }
    private var screen = mainScreen

    /** Lines which scrolled off the top of the main screen, oldest first. */
    val scrollback = ArrayDeque<TerminalRow>()

    /** Number of lines discarded from the head of [scrollback]. */
    var linesDropped = 0L
        private set

    /** Total number of lines ever pushed into scrollback. */
    var scrollCounter = 0L
        private set

    var isAltScreen = false
        private set
    var cursorX = 0
        private set
    var cursorY = 0
        private set

    private var wrapPending = false
    private var style = TextStyle.DEFAULT
    private var scrollTop = 0
    private var scrollBottom = this.rows // exclusive

    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var cursorVisible = true
        private set
    var reverseVideo = false
        private set

    /** 0 = off, otherwise 1000/1002/1003. */
    var mouseTracking = 0
        private set
    var mouseSgr = false
        private set

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var newLineMode = false

    private var tabStops = BooleanArray(this.columns)

    private val charsets = IntArray(4)
    private var glCharset = 0

    var title: String? = null
        private set

    var onTitleChanged: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null

    /** Incremented on every modification, lets views know when to redraw. */
    var revision = 0L
        private set

    private class SavedCursor {
        var x = 0
        var y = 0
        var style = TextStyle.DEFAULT
        var originMode = false
        var autoWrap = true
        var wrapPending = false
        val charsets = IntArray(4)
        var glCharset = 0
        var saved = false
    }

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    // Parser state
    private var state = STATE_GROUND
    private val params = IntArray(MAX_PARAMS)
    private val paramIsSub = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var privateMarker = 0
    private var intermediate = 0
    private val stringBuffer = StringBuilder()
    private var charsetTarget = 0

    // UTF-8 decoder state
    private var utf8Remaining = 0
    private var utf8Codepoint = 0
    private var utf8Min = 0

    private var lastPrinted = ' '.code

    init {
        resetTabStops()
    }

    // ------------------------------------------------------------------ public API

    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = data[i].toInt() and 0xff
            i++
            if (utf8Remaining == 0) {
                when {
                    b < 0x80 -> process(b)
                    b and 0xE0 == 0xC0 -> {
                        utf8Remaining = 1; utf8Codepoint = b and 0x1F; utf8Min = 0x80
                    }
                    b and 0xF0 == 0xE0 -> {
                        utf8Remaining = 2; utf8Codepoint = b and 0x0F; utf8Min = 0x800
                    }
                    b and 0xF8 == 0xF0 -> {
                        utf8Remaining = 3; utf8Codepoint = b and 0x07; utf8Min = 0x10000
                    }
                    else -> process(REPLACEMENT)
                }
            } else {
                if (b and 0xC0 != 0x80) {
                    utf8Remaining = 0
                    process(REPLACEMENT)
                    i-- // reprocess this byte
                    continue
                }
                utf8Codepoint = (utf8Codepoint shl 6) or (b and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) {
                    val cp = utf8Codepoint
                    if (cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) process(REPLACEMENT) else process(cp)
                }
            }
        }
        revision++
    }

    fun append(text: String) = append(text.toByteArray(Charsets.UTF_8))

    /** Returns a row by screen index, taking the scrollback offset into account (negative = scrollback). */
    fun getRow(index: Int): TerminalRow? {
        if (index >= 0) return if (index < rows) screen[index] else null
        if (isAltScreen) return null
        val sbIndex = scrollback.size + index
        return if (sbIndex >= 0) scrollback[sbIndex] else null
    }

    val scrollbackSize: Int get() = if (isAltScreen) 0 else scrollback.size

    /** Absolute index of a screen row. */
    fun absoluteRow(screenRow: Int): Long = linesDropped + scrollbackSize + screenRow

    fun rowByAbsolute(abs: Long): TerminalRow? {
        val rel = abs - linesDropped - scrollbackSize
        if (rel < Int.MIN_VALUE || rel > Int.MAX_VALUE) return null
        return getRow(rel.toInt())
    }

    val firstAbsoluteRow: Long get() = linesDropped
    val lastAbsoluteRow: Long get() = absoluteRow(rows - 1)

    /**
     * Extracts text between two absolute positions (inclusive start, exclusive end column).
     * Soft-wrapped lines are joined.
     */
    fun getText(startRow: Long, startCol: Int, endRow: Long, endCol: Int): String {
        val sb = StringBuilder()
        var r = maxOf(startRow, firstAbsoluteRow)
        val last = minOf(endRow, lastAbsoluteRow)
        while (r <= last) {
            val row = rowByAbsolute(r)
            if (row != null) {
                val from = if (r == startRow) startCol else 0
                val to = if (r == endRow) endCol else row.size
                val wrapsToNext = row.wrapped && r != last
                row.appendText(sb, from, to, trimTrailing = !wrapsToNext)
                if (r != last && !wrapsToNext) sb.append('\n')
            }
            r++
        }
        return sb.toString()
    }

    /** Whole transcript: scrollback and screen, trailing empty lines trimmed. */
    fun getTranscript(): String = getText(firstAbsoluteRow, 0, lastAbsoluteRow, Int.MAX_VALUE).trimEnd('\n', ' ')

    fun resize(newColumns: Int, newRows: Int) {
        val cols = maxOf(newColumns, MIN_COLUMNS)
        val rws = maxOf(newRows, MIN_ROWS)
        if (cols == columns && rws == rows) return

        val mainCursor = if (isAltScreen) (if (savedMain.saved) savedMain.y else rows - 1) else cursorY
        val (newMain, newMainCursor) = resizeScreen(mainScreen, cols, rws, mainCursor, withScrollback = true)
        val (newAlt, newAltCursor) = resizeScreen(altScreen, cols, rws, if (isAltScreen) cursorY else 0, withScrollback = false)
        for (row in scrollback) {
            if (row.size > cols) row.resize(cols, TextStyle.DEFAULT)
        }
        mainScreen = newMain
        altScreen = newAlt
        screen = if (isAltScreen) altScreen else mainScreen
        columns = cols
        rows = rws
        if (isAltScreen) {
            cursorY = newAltCursor
            if (savedMain.saved) savedMain.y = newMainCursor.coerceIn(0, rows - 1)
        } else {
            cursorY = newMainCursor
            savedMain.y = savedMain.y.coerceIn(0, rows - 1)
        }
        cursorX = cursorX.coerceIn(0, columns - 1)
        savedMain.x = savedMain.x.coerceIn(0, columns - 1)
        savedAlt.x = savedAlt.x.coerceIn(0, columns - 1)
        savedAlt.y = savedAlt.y.coerceIn(0, rows - 1)
        scrollTop = 0
        scrollBottom = rows
        wrapPending = false
        resetTabStops()
        revision++
    }

    private fun resizeScreen(
        old: Array<TerminalRow>, cols: Int, rws: Int, cursor: Int, withScrollback: Boolean,
    ): Pair<Array<TerminalRow>, Int> {
        val list = ArrayList<TerminalRow>(old.size)
        old.forEach { list.add(it) }
        var cur = cursor.coerceIn(0, list.size - 1)
        if (rws < list.size) {
            var toRemove = list.size - rws
            // First drop empty lines below the cursor.
            while (toRemove > 0 && list.size - 1 > cur && list[list.size - 1].isBlank()) {
                list.removeAt(list.size - 1)
                toRemove--
            }
            while (toRemove > 0) {
                val top = list.removeAt(0)
                if (withScrollback) pushScrollback(top)
                cur--
                toRemove--
            }
        } else if (rws > list.size) {
            var toAdd = rws - list.size
            if (withScrollback) {
                while (toAdd > 0 && scrollback.isNotEmpty()) {
                    val row = scrollback.removeLast()
                    scrollCounter--
                    list.add(0, row)
                    cur++
                    toAdd--
                }
            }
            while (toAdd > 0) {
                list.add(TerminalRow(cols))
                toAdd--
            }
        }
        for (row in list) row.resize(cols, TextStyle.DEFAULT)
        return Pair(list.toTypedArray(), cur.coerceIn(0, rws - 1))
    }

    fun reset() {
        style = TextStyle.DEFAULT
        isAltScreen = false
        screen = mainScreen
        for (r in mainScreen) r.clearAll(TextStyle.DEFAULT)
        for (r in altScreen) r.clearAll(TextStyle.DEFAULT)
        cursorX = 0
        cursorY = 0
        wrapPending = false
        scrollTop = 0
        scrollBottom = rows
        applicationCursorKeys = false
        applicationKeypad = false
        bracketedPaste = false
        cursorVisible = true
        reverseVideo = false
        mouseTracking = 0
        mouseSgr = false
        autoWrap = true
        originMode = false
        insertMode = false
        newLineMode = false
        charsets.fill(CHARSET_ASCII)
        glCharset = 0
        savedMain.saved = false
        savedAlt.saved = false
        state = STATE_GROUND
        resetTabStops()
        revision++
    }

    /** Leaves full-screen applications state behind after the connection was lost. */
    fun resetForReconnect() {
        switchToMain(false)
        softReset()
        cursorVisible = true
        bracketedPaste = false
        mouseTracking = 0
        mouseSgr = false
        reverseVideo = false
        if (cursorX > 0) {
            carriageReturn()
            lineFeed()
        }
        revision++
    }

    fun clearScrollback() {
        linesDropped += scrollback.size
        scrollback.clear()
        revision++
    }

    // ------------------------------------------------------------------ parser

    private fun process(cp: Int) {
        when (state) {
            STATE_GROUND -> {
                if (cp < 0x20 || cp == 0x7F) control(cp) else print(cp)
            }
            STATE_ESCAPE -> {
                if (cp < 0x20) {
                    controlInSequence(cp)
                } else {
                    escape(cp)
                }
            }
            STATE_ESCAPE_INTERMEDIATE -> {
                if (cp < 0x20) controlInSequence(cp)
                else if (cp in 0x20..0x2F) Unit
                else state = STATE_GROUND
            }
            STATE_CHARSET -> {
                charsets[charsetTarget] = if (cp == '0'.code) CHARSET_DEC_GRAPHICS else CHARSET_ASCII
                state = STATE_GROUND
            }
            STATE_HASH -> {
                if (cp == '8'.code) decAlignmentTest()
                state = STATE_GROUND
            }
            STATE_CSI -> csi(cp)
            STATE_OSC -> {
                when (cp) {
                    0x07 -> {
                        handleOsc(); state = STATE_GROUND
                    }
                    0x1B -> state = STATE_OSC_ESC
                    0x18, 0x1A -> state = STATE_GROUND
                    else -> if (stringBuffer.length < MAX_STRING) stringBuffer.appendCodePoint(cp)
                }
            }
            STATE_OSC_ESC -> {
                handleOsc()
                if (cp == '\\'.code) {
                    state = STATE_GROUND
                } else {
                    state = STATE_ESCAPE
                    process(cp)
                }
            }
            STATE_IGNORE_STRING -> {
                when (cp) {
                    0x1B -> state = STATE_IGNORE_STRING_ESC
                    0x07, 0x18, 0x1A -> state = STATE_GROUND
                }
            }
            STATE_IGNORE_STRING_ESC -> {
                state = if (cp == '\\'.code) STATE_GROUND else STATE_IGNORE_STRING
            }
        }
    }

    private fun controlInSequence(cp: Int) {
        when (cp) {
            0x1B -> startEscape()
            0x18, 0x1A -> state = STATE_GROUND
            else -> control(cp)
        }
    }

    private fun startEscape() {
        state = STATE_ESCAPE
    }

    private fun control(cp: Int) {
        when (cp) {
            0x07 -> onBell?.invoke()
            0x08 -> { // BS
                if (cursorX > 0) cursorX--
                wrapPending = false
            }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> {
                lineFeed()
                if (newLineMode) carriageReturn()
            }
            0x0D -> carriageReturn()
            0x0E -> glCharset = 1
            0x0F -> glCharset = 0
            0x1B -> startEscape()
            0x18, 0x1A -> state = STATE_GROUND
        }
    }

    private fun escape(cp: Int) {
        state = STATE_GROUND
        when (cp.toChar()) {
            '[' -> {
                paramCount = 0
                params.fill(-1)
                paramIsSub.fill(false)
                privateMarker = 0
                intermediate = 0
                state = STATE_CSI
            }
            ']' -> {
                stringBuffer.setLength(0)
                state = STATE_OSC
            }
            'P', 'X', '^', '_' -> state = STATE_IGNORE_STRING
            '(' -> { charsetTarget = 0; state = STATE_CHARSET }
            ')' -> { charsetTarget = 1; state = STATE_CHARSET }
            '*' -> { charsetTarget = 2; state = STATE_CHARSET }
            '+' -> { charsetTarget = 3; state = STATE_CHARSET }
            '#' -> state = STATE_HASH
            ' ', '%', '!', '"', '$', '&', '\'', ',', '-', '.', '/' -> state = STATE_ESCAPE_INTERMEDIATE
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { carriageReturn(); lineFeed() }
            'H' -> if (cursorX < columns) tabStops[cursorX] = true
            'M' -> reverseIndex()
            'c' -> reset()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            'Z' -> sendString("\u001b[?1;2c")
            else -> Unit // ignore unknown / ST
        }
    }

    private fun csi(cp: Int) {
        when {
            cp in '0'.code..'9'.code -> {
                if (paramCount == 0) paramCount = 1
                val idx = paramCount - 1
                if (idx < MAX_PARAMS) {
                    val cur = if (params[idx] < 0) 0 else params[idx]
                    params[idx] = minOf(cur * 10 + (cp - '0'.code), 99999)
                }
            }
            cp == ';'.code || cp == ':'.code -> {
                if (paramCount == 0) paramCount = 1
                if (paramCount < MAX_PARAMS) {
                    paramIsSub[paramCount] = cp == ':'.code
                    paramCount++
                }
            }
            cp in 0x3C..0x3F -> { // < = > ?
                if (paramCount == 0) privateMarker = cp
            }
            cp in 0x20..0x2F -> intermediate = cp
            cp in 0x40..0x7E -> {
                state = STATE_GROUND
                dispatchCsi(cp)
            }
            cp < 0x20 -> controlInSequence(cp)
            else -> state = STATE_GROUND
        }
    }

    private fun p(i: Int, def: Int): Int {
        if (i >= paramCount) return def
        val v = params[i]
        return if (v < 0) def else v
    }

    /** Parameter where 0 is treated like the default 1 (cursor movement etc). */
    private fun p1(i: Int): Int = maxOf(1, p(i, 1))

    private fun dispatchCsi(cmd: Int) {
        if (privateMarker == '?'.code) {
            when (cmd.toChar()) {
                'h' -> for (i in 0 until maxOf(paramCount, 1)) setDecMode(p(i, 0), true)
                'l' -> for (i in 0 until maxOf(paramCount, 1)) setDecMode(p(i, 0), false)
                'J' -> eraseInDisplay(p(0, 0))
                'K' -> eraseInLine(p(0, 0))
                's', 'r', 'n', 'p' -> Unit
            }
            return
        }
        if (privateMarker == '>'.code) {
            if (cmd == 'c'.code) sendString("\u001b[>1;10;0c")
            return
        }
        if (privateMarker != 0) return
        if (intermediate != 0) {
            if (intermediate == '!'.code && cmd == 'p'.code) softReset()
            // DECSCUSR (cursor style) and others are ignored
            return
        }
        when (cmd.toChar()) {
            '@' -> insertChars(p1(0))
            'A' -> cursorUp(p1(0))
            'B', 'e' -> cursorDown(p1(0))
            'C', 'a' -> cursorForward(p1(0))
            'D' -> cursorBackward(p1(0))
            'E' -> { cursorDown(p1(0)); cursorX = 0 }
            'F' -> { cursorUp(p1(0)); cursorX = 0 }
            'G', '`' -> setCursorX(p1(0) - 1)
            'H', 'f' -> setCursorPosition(p1(1) - 1, p1(0) - 1)
            'I' -> tab(p1(0))
            'J' -> eraseInDisplay(p(0, 0))
            'K' -> eraseInLine(p(0, 0))
            'L' -> insertLines(p1(0))
            'M' -> deleteLines(p1(0))
            'P' -> deleteChars(p1(0))
            'S' -> scrollUp(p1(0), false)
            'T' -> scrollDown(p1(0))
            'X' -> eraseChars(p1(0))
            'Z' -> backTab(p1(0))
            'b' -> repeatLast(p1(0))
            'c' -> if (p(0, 0) == 0) sendString("\u001b[?1;2c")
            'd' -> setCursorY(p1(0) - 1)
            'g' -> when (p(0, 0)) {
                0 -> if (cursorX < columns) tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h' -> for (i in 0 until maxOf(paramCount, 1)) setAnsiMode(p(i, 0), true)
            'l' -> for (i in 0 until maxOf(paramCount, 1)) setAnsiMode(p(i, 0), false)
            'm' -> selectGraphicRendition()
            'n' -> when (p(0, 0)) {
                5 -> sendString("\u001b[0n")
                6 -> {
                    val y = if (originMode) cursorY - scrollTop else cursorY
                    sendString("\u001b[${y + 1};${cursorX + 1}R")
                }
            }
            'r' -> setScrollRegion(p(0, 1), p(1, rows))
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> if (p(0, 0) == 18) sendString("\u001b[8;${rows};${columns}t")
        }
    }

    private fun setDecMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            5 -> reverseVideo = on
            6 -> {
                originMode = on
                setCursorPosition(0, 0)
            }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047 -> {
                if (on) switchToAlt(false) else switchToMain(mode == 1047)
            }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) {
                    saveCursor()
                    switchToAlt(true)
                } else {
                    switchToMain(false)
                    restoreCursor()
                }
            }
            1000, 1002, 1003 -> mouseTracking = if (on) mode else 0
            1006 -> mouseSgr = on
            2004 -> bracketedPaste = on
        }
    }

    private fun setAnsiMode(mode: Int, on: Boolean) {
        when (mode) {
            4 -> insertMode = on
            20 -> newLineMode = on
        }
    }

    private fun switchToAlt(clear: Boolean) {
        if (isAltScreen) {
            if (clear) for (r in altScreen) r.clearAll(eraseStyle())
            return
        }
        isAltScreen = true
        screen = altScreen
        if (clear) for (r in altScreen) r.clearAll(eraseStyle())
    }

    private fun switchToMain(clearAlt: Boolean) {
        if (!isAltScreen) return
        if (clearAlt) for (r in altScreen) r.clearAll(TextStyle.DEFAULT)
        isAltScreen = false
        screen = mainScreen
    }

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        scrollTop = 0
        scrollBottom = rows
        style = TextStyle.DEFAULT
        charsets.fill(CHARSET_ASCII)
        glCharset = 0
        wrapPending = false
    }

    private fun selectGraphicRendition() {
        if (paramCount == 0) {
            style = TextStyle.DEFAULT
            return
        }
        var i = 0
        while (i < paramCount) {
            val code = p(i, 0)
            when (code) {
                0 -> style = TextStyle.DEFAULT
                1 -> addFlag(TextStyle.BOLD)
                2 -> addFlag(TextStyle.DIM)
                3 -> addFlag(TextStyle.ITALIC)
                4 -> {
                    // 4:0 means "no underline", other sub params are underline styles
                    if (i + 1 < paramCount && paramIsSub[i + 1]) {
                        if (p(i + 1, 1) == 0) removeFlag(TextStyle.UNDERLINE) else addFlag(TextStyle.UNDERLINE)
                        i++
                        while (i + 1 < paramCount && paramIsSub[i + 1]) i++
                    } else {
                        addFlag(TextStyle.UNDERLINE)
                    }
                }
                5, 6 -> addFlag(TextStyle.BLINK)
                7 -> addFlag(TextStyle.INVERSE)
                8 -> addFlag(TextStyle.INVISIBLE)
                9 -> addFlag(TextStyle.STRIKETHROUGH)
                21 -> addFlag(TextStyle.UNDERLINE)
                22 -> removeFlag(TextStyle.BOLD or TextStyle.DIM)
                23 -> removeFlag(TextStyle.ITALIC)
                24 -> removeFlag(TextStyle.UNDERLINE)
                25 -> removeFlag(TextStyle.BLINK)
                27 -> removeFlag(TextStyle.INVERSE)
                28 -> removeFlag(TextStyle.INVISIBLE)
                29 -> removeFlag(TextStyle.STRIKETHROUGH)
                in 30..37 -> style = TextStyle.withFg(style, code - 30)
                38, 48, 58 -> {
                    val (color, consumed) = parseExtendedColor(i)
                    i += consumed
                    if (color >= 0) {
                        if (code == 38) style = TextStyle.withFg(style, color)
                        else if (code == 48) style = TextStyle.withBg(style, color)
                    }
                }
                39 -> style = TextStyle.withFg(style, TextStyle.COLOR_DEFAULT_FG)
                in 40..47 -> style = TextStyle.withBg(style, code - 40)
                49 -> style = TextStyle.withBg(style, TextStyle.COLOR_DEFAULT_BG)
                in 90..97 -> style = TextStyle.withFg(style, code - 90 + 8)
                in 100..107 -> style = TextStyle.withBg(style, code - 100 + 8)
            }
            i++
        }
    }

    /** Returns color and number of extra parameters consumed. */
    private fun parseExtendedColor(i: Int): Pair<Int, Int> {
        if (i + 1 < paramCount && paramIsSub[i + 1]) {
            // colon form: 38:5:n or 38:2:[cs]:r:g:b
            var n = 0
            while (i + 1 + n < paramCount && paramIsSub[i + 1 + n]) n++
            val mode = p(i + 1, -1)
            return when {
                mode == 5 && n >= 2 -> Pair(p(i + 2, 0).coerceIn(0, 255), n)
                mode == 2 && n >= 5 -> Pair(TextStyle.rgb(p(i + 3, 0), p(i + 4, 0), p(i + 5, 0)), n)
                mode == 2 && n >= 4 -> Pair(TextStyle.rgb(p(i + 2, 0), p(i + 3, 0), p(i + 4, 0)), n)
                else -> Pair(-1, n)
            }
        }
        val mode = p(i + 1, -1)
        return when (mode) {
            5 -> if (i + 2 < paramCount) Pair(p(i + 2, 0).coerceIn(0, 255), 2) else Pair(-1, paramCount - i - 1)
            2 -> if (i + 4 < paramCount) Pair(TextStyle.rgb(p(i + 2, 0), p(i + 3, 0), p(i + 4, 0)), 4) else Pair(-1, paramCount - i - 1)
            else -> Pair(-1, if (i + 1 < paramCount) 1 else 0)
        }
    }

    private fun addFlag(flag: Long) {
        style = TextStyle.withFlags(style, TextStyle.flags(style) or flag)
    }

    private fun removeFlag(flag: Long) {
        style = TextStyle.withFlags(style, TextStyle.flags(style) and flag.inv())
    }

    private fun handleOsc() {
        val s = stringBuffer.toString()
        val sep = s.indexOf(';')
        if (sep <= 0) return
        val code = s.substring(0, sep).toIntOrNull() ?: return
        val arg = s.substring(sep + 1)
        when (code) {
            0, 2 -> {
                title = arg
                onTitleChanged?.invoke(arg)
            }
        }
    }

    private fun sendString(s: String) {
        output(s.toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ operations

    private fun eraseStyle(): Long = TextStyle.encode(TextStyle.COLOR_DEFAULT_FG, TextStyle.bg(style), 0)

    private fun print(codePoint: Int) {
        var cp = codePoint
        if (charsets[glCharset] == CHARSET_DEC_GRAPHICS && cp in 0x5F..0x7E) {
            cp = DEC_GRAPHICS[cp - 0x5F].code
        }
        val width = WcWidth.width(cp)
        if (width == 0) return
        if (wrapPending) {
            wrapPending = false
            if (autoWrap) {
                screen[cursorY].wrapped = true
                carriageReturn()
                lineFeed()
            }
        }
        if (width == 2 && cursorX >= columns - 1) {
            if (columns < 2) return
            if (autoWrap) {
                val row = screen[cursorY]
                clearWideAt(row, cursorX)
                row.text[cursorX] = 0
                row.wrapped = true
                carriageReturn()
                lineFeed()
            } else {
                cursorX = columns - 2
            }
        }
        if (insertMode) insertChars(width)
        val row = screen[cursorY]
        clearWideAt(row, cursorX)
        if (width == 2) clearWideAt(row, cursorX + 1)
        row.text[cursorX] = cp
        row.style[cursorX] = style
        if (width == 2) {
            row.text[cursorX + 1] = TerminalRow.WIDE_TAIL
            row.style[cursorX + 1] = style
        }
        lastPrinted = cp
        val next = cursorX + width
        if (next >= columns) {
            cursorX = columns - 1
            wrapPending = autoWrap
        } else {
            cursorX = next
        }
    }

    /** Before overwriting cell [x], make sure no orphaned half of a wide char remains. */
    private fun clearWideAt(row: TerminalRow, x: Int) {
        if (x < 0 || x >= row.size) return
        if (row.text[x] == TerminalRow.WIDE_TAIL && x > 0) {
            row.text[x - 1] = 0
            row.text[x] = 0
        }
        if (x + 1 < row.size && row.text[x + 1] == TerminalRow.WIDE_TAIL) {
            row.text[x + 1] = 0
        }
    }

    private fun repeatLast(n: Int) {
        val count = minOf(n, columns * rows)
        repeat(count) { print(lastPrinted) }
    }

    private fun carriageReturn() {
        cursorX = 0
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == scrollBottom - 1) {
            scrollUp(1, true)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == scrollTop) {
            scrollDown(1)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    private fun pushScrollback(row: TerminalRow) {
        scrollback.addLast(row)
        scrollCounter++
        while (scrollback.size > maxScrollback) {
            scrollback.removeFirst()
            linesDropped++
        }
    }

    private fun scrollUp(n: Int, toScrollback: Boolean) {
        val count = minOf(n, scrollBottom - scrollTop)
        repeat(count) {
            val keep = toScrollback && !isAltScreen && scrollTop == 0
            val top = screen[scrollTop]
            for (y in scrollTop until scrollBottom - 1) screen[y] = screen[y + 1]
            val newRow = if (keep) {
                pushScrollback(top)
                TerminalRow(columns, eraseStyle())
            } else {
                top.clearAll(eraseStyle())
                top
            }
            screen[scrollBottom - 1] = newRow
        }
    }

    private fun scrollDown(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop)
        repeat(count) {
            val bottom = screen[scrollBottom - 1]
            for (y in scrollBottom - 1 downTo scrollTop + 1) screen[y] = screen[y - 1]
            bottom.clearAll(eraseStyle())
            screen[scrollTop] = bottom
        }
    }

    private fun tab(n: Int) {
        var x = cursorX
        repeat(n) {
            x++
            while (x < columns - 1 && !tabStops[x]) x++
        }
        cursorX = minOf(x, columns - 1)
    }

    private fun backTab(n: Int) {
        var x = cursorX
        repeat(n) {
            if (x > 0) x--
            while (x > 0 && !tabStops[x]) x--
        }
        cursorX = x
        wrapPending = false
    }

    private fun resetTabStops() {
        tabStops = BooleanArray(columns) { it != 0 && it % 8 == 0 }
    }

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= scrollTop) scrollTop else 0
        cursorY = maxOf(limit, cursorY - n)
        wrapPending = false
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY < scrollBottom) scrollBottom - 1 else rows - 1
        cursorY = minOf(limit, cursorY + n)
        wrapPending = false
    }

    private fun cursorForward(n: Int) {
        cursorX = minOf(columns - 1, cursorX + n)
        wrapPending = false
    }

    private fun cursorBackward(n: Int) {
        cursorX = maxOf(0, cursorX - n)
        wrapPending = false
    }

    private fun setCursorX(x: Int) {
        cursorX = x.coerceIn(0, columns - 1)
        wrapPending = false
    }

    private fun setCursorY(y: Int) {
        cursorY = if (originMode) (y + scrollTop).coerceIn(scrollTop, scrollBottom - 1) else y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun setCursorPosition(x: Int, y: Int) {
        setCursorX(x)
        setCursorY(y)
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        val t = maxOf(1, top) - 1
        val b = minOf(if (bottom <= 0) rows else bottom, rows)
        if (t < b - 1) {
            scrollTop = t
            scrollBottom = b
            setCursorPosition(0, 0)
        }
    }

    private fun eraseInDisplay(mode: Int) {
        val es = eraseStyle()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in cursorY + 1 until rows) screen[y].clearAll(es)
            }
            1 -> {
                for (y in 0 until cursorY) screen[y].clearAll(es)
                eraseInLine(1)
            }
            2 -> for (y in 0 until rows) screen[y].clearAll(es)
            3 -> if (!isAltScreen) clearScrollback()
        }
        wrapPending = false
    }

    private fun eraseInLine(mode: Int) {
        val row = screen[cursorY]
        val es = eraseStyle()
        when (mode) {
            0 -> {
                clearWideAt(row, cursorX)
                row.clear(cursorX, columns, es)
                row.wrapped = false
            }
            1 -> {
                if (cursorX + 1 < columns) clearWideAt(row, cursorX + 1)
                row.clear(0, cursorX + 1, es)
            }
            2 -> row.clearAll(es)
        }
        wrapPending = false
    }

    private fun eraseChars(n: Int) {
        val row = screen[cursorY]
        val end = minOf(columns, cursorX + n)
        clearWideAt(row, cursorX)
        if (end < columns) clearWideAt(row, end)
        row.clear(cursorX, end, eraseStyle())
        wrapPending = false
    }

    private fun insertChars(n: Int) {
        val row = screen[cursorY]
        val count = minOf(n, columns - cursorX)
        clearWideAt(row, cursorX)
        val text = row.text
        val st = row.style
        for (x in columns - 1 downTo cursorX + count) {
            text[x] = text[x - count]
            st[x] = st[x - count]
        }
        // a wide character whose second half was pushed out of the line
        if (text[columns - 1] > 0 && WcWidth.width(text[columns - 1]) == 2) text[columns - 1] = 0
        row.clear(cursorX, cursorX + count, eraseStyle())
        wrapPending = false
    }

    private fun deleteChars(n: Int) {
        val row = screen[cursorY]
        val count = minOf(n, columns - cursorX)
        clearWideAt(row, cursorX)
        if (cursorX + count < columns) clearWideAt(row, cursorX + count)
        val text = row.text
        val st = row.style
        for (x in cursorX until columns - count) {
            text[x] = text[x + count]
            st[x] = st[x + count]
        }
        row.clear(columns - count, columns, eraseStyle())
        wrapPending = false
    }

    private fun insertLines(n: Int) {
        if (cursorY < scrollTop || cursorY >= scrollBottom) return
        val count = minOf(n, scrollBottom - cursorY)
        repeat(count) {
            val bottom = screen[scrollBottom - 1]
            for (y in scrollBottom - 1 downTo cursorY + 1) screen[y] = screen[y - 1]
            bottom.clearAll(eraseStyle())
            screen[cursorY] = bottom
        }
        cursorX = 0
        wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < scrollTop || cursorY >= scrollBottom) return
        val count = minOf(n, scrollBottom - cursorY)
        repeat(count) {
            val top = screen[cursorY]
            for (y in cursorY until scrollBottom - 1) screen[y] = screen[y + 1]
            top.clearAll(eraseStyle())
            screen[scrollBottom - 1] = top
        }
        cursorX = 0
        wrapPending = false
    }

    private fun saveCursor() {
        val s = if (isAltScreen) savedAlt else savedMain
        s.x = cursorX
        s.y = cursorY
        s.style = style
        s.originMode = originMode
        s.autoWrap = autoWrap
        s.wrapPending = wrapPending
        charsets.copyInto(s.charsets)
        s.glCharset = glCharset
        s.saved = true
    }

    private fun restoreCursor() {
        val s = if (isAltScreen) savedAlt else savedMain
        if (!s.saved) {
            cursorX = 0
            cursorY = 0
            wrapPending = false
            return
        }
        cursorX = s.x.coerceIn(0, columns - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        style = s.style
        originMode = s.originMode
        autoWrap = s.autoWrap
        wrapPending = s.wrapPending
        s.charsets.copyInto(charsets)
        glCharset = s.glCharset
    }

    private fun decAlignmentTest() {
        for (r in screen) {
            r.clearAll(TextStyle.DEFAULT)
            r.text.fill('E'.code)
        }
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = rows
    }

    companion object {
        const val MIN_COLUMNS = 4
        const val MIN_ROWS = 2
        private const val MAX_PARAMS = 32
        private const val MAX_STRING = 4096
        private const val REPLACEMENT = 0xFFFD

        private const val STATE_GROUND = 0
        private const val STATE_ESCAPE = 1
        private const val STATE_ESCAPE_INTERMEDIATE = 2
        private const val STATE_CHARSET = 3
        private const val STATE_HASH = 4
        private const val STATE_CSI = 5
        private const val STATE_OSC = 6
        private const val STATE_OSC_ESC = 7
        private const val STATE_IGNORE_STRING = 8
        private const val STATE_IGNORE_STRING_ESC = 9

        private const val CHARSET_ASCII = 0
        private const val CHARSET_DEC_GRAPHICS = 1

        /** DEC special graphics for 0x5F..0x7E. */
        private const val DEC_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋┘┐┌└┼" +
                "⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}
