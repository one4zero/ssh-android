package com.one4zero.sshterm.terminal

/**
 * One line of the terminal. [text] holds Unicode code points, 0 means an empty cell
 * and [WIDE_TAIL] marks the second half of a double-width character.
 */
class TerminalRow(columns: Int, fillStyle: Long = TextStyle.DEFAULT) {
    var text = IntArray(columns)
        private set
    var style = LongArray(columns).also { it.fill(fillStyle) }
        private set

    /** True when the line was soft-wrapped into the next row. */
    var wrapped = false

    val size: Int get() = text.size

    fun clear(from: Int, to: Int, fillStyle: Long) {
        val end = minOf(to, text.size)
        var i = maxOf(0, from)
        while (i < end) {
            text[i] = 0
            style[i] = fillStyle
            i++
        }
    }

    fun clearAll(fillStyle: Long) {
        clear(0, text.size, fillStyle)
        wrapped = false
    }

    fun resize(columns: Int, fillStyle: Long) {
        if (columns == text.size) return
        val newText = IntArray(columns)
        val newStyle = LongArray(columns).also { it.fill(fillStyle) }
        val n = minOf(columns, text.size)
        System.arraycopy(text, 0, newText, 0, n)
        System.arraycopy(style, 0, newStyle, 0, n)
        // Do not leave half of a wide character at the right edge.
        if (n in 1 until text.size && newText[n - 1] != WIDE_TAIL && n < text.size && text[n] == WIDE_TAIL) {
            newText[n - 1] = 0
        }
        text = newText
        style = newStyle
        if (columns < n) wrapped = false
    }

    fun isBlank(): Boolean {
        for (c in text) if (c != 0 && c != ' '.code) return false
        return true
    }

    /** Text of the row with trailing blanks removed. */
    fun toText(from: Int = 0, to: Int = text.size): String {
        val sb = StringBuilder()
        appendText(sb, from, to)
        return sb.toString()
    }

    fun appendText(sb: StringBuilder, from: Int, to: Int, trimTrailing: Boolean = true) {
        val end = minOf(to, text.size)
        val start = sb.length
        var lastNonBlank = sb.length
        var i = maxOf(0, from)
        while (i < end) {
            val c = text[i]
            if (c == WIDE_TAIL) {
                i++
                continue
            }
            if (c == 0) {
                sb.append(' ')
            } else {
                sb.appendCodePoint(c)
                if (c != ' '.code) lastNonBlank = sb.length
            }
            i++
        }
        if (trimTrailing) {
            sb.setLength(maxOf(start, lastNonBlank))
        }
    }

    companion object {
        const val WIDE_TAIL = -1
    }
}
