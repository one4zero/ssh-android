package com.one4zero.sshterm.terminal

/** Special (non character) keys of a terminal keyboard. */
enum class TermKey {
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    ENTER, TAB, BACKSPACE, ESCAPE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Converts key presses into the byte sequences an xterm would send. */
object KeyEncoder {
    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4

    fun encodeKey(key: TermKey, modifiers: Int, appCursor: Boolean): String {
        val modParam = if (modifiers != 0) 1 + modifiers else 0
        fun cursor(c: Char): String = when {
            modParam != 0 -> "\u001b[1;$modParam$c"
            appCursor -> "\u001bO$c"
            else -> "\u001b[$c"
        }
        fun tilde(n: Int): String = if (modParam != 0) "\u001b[$n;$modParam~" else "\u001b[$n~"
        fun ss3(c: Char): String = if (modParam != 0) "\u001b[1;$modParam$c" else "\u001bO$c"
        val altPrefix = if (modifiers and MOD_ALT != 0) "\u001b" else ""
        return when (key) {
            TermKey.UP -> cursor('A')
            TermKey.DOWN -> cursor('B')
            TermKey.RIGHT -> cursor('C')
            TermKey.LEFT -> cursor('D')
            TermKey.HOME -> cursor('H')
            TermKey.END -> cursor('F')
            TermKey.PAGE_UP -> tilde(5)
            TermKey.PAGE_DOWN -> tilde(6)
            TermKey.INSERT -> tilde(2)
            TermKey.DELETE -> tilde(3)
            TermKey.ENTER -> altPrefix + "\r"
            TermKey.TAB -> if (modifiers and MOD_SHIFT != 0) "\u001b[Z" else altPrefix + "\t"
            TermKey.BACKSPACE -> altPrefix + if (modifiers and MOD_CTRL != 0) "\u0008" else "\u007f"
            TermKey.ESCAPE -> altPrefix + "\u001b"
            TermKey.F1 -> ss3('P')
            TermKey.F2 -> ss3('Q')
            TermKey.F3 -> ss3('R')
            TermKey.F4 -> ss3('S')
            TermKey.F5 -> tilde(15)
            TermKey.F6 -> tilde(17)
            TermKey.F7 -> tilde(18)
            TermKey.F8 -> tilde(19)
            TermKey.F9 -> tilde(20)
            TermKey.F10 -> tilde(21)
            TermKey.F11 -> tilde(23)
            TermKey.F12 -> tilde(24)
        }
    }

    /** Returns the control character for Ctrl+[cp], or -1 if there is none. */
    fun controlChar(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '@'.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '-'.code, '/'.code, '7'.code -> 31
        '?'.code, '8'.code -> 127
        // Cyrillic letters on the same keys as latin ones (ЙЦУКЕН layout)
        else -> cyrillicToLatin(cp).let { if (it > 0) controlChar(it) else -1 }
    }

    private const val CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбю"
    private const val LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,."

    private fun cyrillicToLatin(cp: Int): Int {
        val lower = Character.toLowerCase(cp)
        val idx = CYRILLIC.indexOf(lower.toChar())
        if (idx < 0 || cp > 0xFFFF) return -1
        val c = LATIN[idx]
        return if (c in 'a'..'z') c.code else -1
    }

    /** Encodes typed text applying the Ctrl / Alt modifiers to the first character. */
    fun encodeText(text: String, ctrl: Boolean, alt: Boolean): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        var first = true
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            var out = if (cp == '\n'.code) '\r'.code else cp
            if (first && ctrl) {
                val c = controlChar(cp)
                if (c >= 0) out = c
            }
            if (first && alt) sb.append('\u001b')
            sb.appendCodePoint(out)
            first = false
        }
        return sb.toString()
    }

    /** SGR (1006) or legacy mouse wheel report. [up] = scroll towards older content. */
    fun mouseWheel(up: Boolean, col: Int, row: Int, sgr: Boolean): String {
        val button = if (up) 64 else 65
        return if (sgr) {
            "\u001b[<$button;${col + 1};${row + 1}M"
        } else {
            val cb = (32 + button).toChar()
            val cx = (32 + minOf(col + 1, 223)).toChar()
            val cy = (32 + minOf(row + 1, 223)).toChar()
            "\u001b[M$cb$cx$cy"
        }
    }
}
