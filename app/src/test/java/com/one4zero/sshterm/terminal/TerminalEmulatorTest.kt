package com.one4zero.sshterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    private val responses = StringBuilder()

    private fun emulator(cols: Int = 10, rows: Int = 4, scrollback: Int = 100) =
        TerminalEmulator(cols, rows, scrollback) { responses.append(String(it, Charsets.UTF_8)) }

    private fun TerminalEmulator.line(y: Int) = getRow(y)!!.toText()

    @Test
    fun printsAndWraps() {
        val e = emulator()
        e.append("hello world!")
        assertEquals("hello worl", e.line(0))
        assertEquals("d!", e.line(1))
        assertTrue(e.getRow(0)!!.wrapped)
        assertEquals(2, e.cursorX)
        assertEquals(1, e.cursorY)
    }

    @Test
    fun pendingWrapAtLastColumn() {
        val e = emulator(cols = 5)
        e.append("abcde")
        assertEquals(4, e.cursorX)
        assertEquals(0, e.cursorY)
        e.append("\r\n")
        assertEquals(1, e.cursorY)
        assertEquals("abcde", e.line(0))
        assertFalse(e.getRow(0)!!.wrapped)
    }

    @Test
    fun utf8AndCyrillic() {
        val e = emulator(cols = 20)
        e.append("Привет, мир".toByteArray(Charsets.UTF_8))
        assertEquals("Привет, мир", e.line(0))
        assertEquals(11, e.cursorX)
    }

    @Test
    fun utf8SplitAcrossChunks() {
        val e = emulator(cols = 20)
        val bytes = "ЖЖ".toByteArray(Charsets.UTF_8)
        e.append(bytes, 0, 1)
        e.append(bytes, 1, 2)
        e.append(bytes, 3, 1)
        assertEquals("ЖЖ", e.line(0))
    }

    @Test
    fun wideCharacters() {
        val e = emulator(cols = 6)
        e.append("a中b")
        assertEquals("a中b", e.line(0))
        assertEquals(4, e.cursorX)
        e.append("\u001b[3G") // overwrite the tail of the wide char
        e.append("x")
        assertEquals("a xb", e.line(0))
    }

    @Test
    fun scrollingIntoScrollback() {
        val e = emulator(cols = 10, rows = 3)
        e.append("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, e.scrollback.size)
        assertEquals("1", e.scrollback[0].toText())
        assertEquals("3", e.line(0))
        assertEquals("5", e.line(2))
        assertEquals("1\n2\n3\n4\n5", e.getTranscript())
    }

    @Test
    fun scrollbackLimit() {
        val e = emulator(cols = 10, rows = 2, scrollback = 3)
        for (i in 1..10) e.append("$i\r\n")
        assertEquals(3, e.scrollback.size)
        assertEquals(6L, e.linesDropped)
        assertEquals("7", e.scrollback[0].toText())
    }

    @Test
    fun absoluteRowsStable() {
        val e = emulator(cols = 10, rows = 3)
        e.append("a\r\nb\r\n")
        val abs = e.absoluteRow(1) // "b"
        e.append("c\r\nd\r\ne\r\n")
        assertEquals("b", e.rowByAbsolute(abs)!!.toText())
    }

    @Test
    fun cursorMovementAndErase() {
        val e = emulator(cols = 10, rows = 4)
        e.append("abcdefghij\r\n0123456789")
        e.append("\u001b[1;3H")
        e.append("\u001b[K")
        assertEquals("ab", e.line(0))
        e.append("\u001b[2;5H\u001b[1K")
        assertEquals("     56789", e.getRow(1)!!.toText())
        e.append("\u001b[2J")
        assertEquals("", e.line(0))
        assertEquals("", e.line(1))
    }

    @Test
    fun sgrColors() {
        val e = emulator()
        e.append("\u001b[1;31mR\u001b[38;5;100mX\u001b[38;2;1;2;3mT\u001b[48:2::10:20:30mU\u001b[0mN")
        val row = e.getRow(0)!!
        assertEquals(1, TextStyle.fg(row.style[0]))
        assertTrue(TextStyle.flags(row.style[0]) and TextStyle.BOLD != 0L)
        assertEquals(100, TextStyle.fg(row.style[1]))
        assertEquals(TextStyle.rgb(1, 2, 3), TextStyle.fg(row.style[2]))
        assertEquals(TextStyle.rgb(10, 20, 30), TextStyle.bg(row.style[3]))
        assertEquals(TextStyle.DEFAULT, row.style[4])
    }

    @Test
    fun alternateScreen() {
        val e = emulator(cols = 10, rows = 3)
        e.append("main")
        e.append("\u001b[?1049h")
        assertTrue(e.isAltScreen)
        assertEquals("", e.line(0))
        e.append("alt")
        e.append("\u001b[?1049l")
        assertFalse(e.isAltScreen)
        assertEquals("main", e.line(0))
        assertEquals(4, e.cursorX)
    }

    @Test
    fun scrollRegion() {
        val e = emulator(cols = 10, rows = 4)
        e.append("1\r\n2\r\n3\r\n4")
        e.append("\u001b[2;3r") // region rows 2..3
        e.append("\u001b[3;1H\n") // LF at bottom of region
        assertEquals("1", e.line(0))
        assertEquals("3", e.line(1))
        assertEquals("", e.line(2))
        assertEquals("4", e.line(3))
        assertEquals(0, e.scrollback.size)
    }

    @Test
    fun insertAndDeleteLines() {
        val e = emulator(cols = 10, rows = 4)
        e.append("1\r\n2\r\n3\r\n4")
        e.append("\u001b[2;1H\u001b[L")
        assertEquals(listOf("1", "", "2", "3"), (0..3).map { e.line(it) })
        e.append("\u001b[2M")
        assertEquals(listOf("1", "3", "", ""), (0..3).map { e.line(it) })
    }

    @Test
    fun insertDeleteChars() {
        val e = emulator(cols = 10)
        e.append("abcdef\u001b[1;3H\u001b[2@")
        assertEquals("ab  cdef", e.line(0))
        e.append("\u001b[3P")
        assertEquals("abdef", e.line(0))
    }

    @Test
    fun deviceStatusReport() {
        val e = emulator()
        e.append("\u001b[3;5H\u001b[6n")
        assertEquals("\u001b[3;5R", responses.toString())
    }

    @Test
    fun oscTitle() {
        val e = emulator()
        var t: String? = null
        e.onTitleChanged = { t = it }
        e.append("\u001b]0;user@host: ~\u0007x")
        assertEquals("user@host: ~", t)
        assertEquals("x", e.line(0))
        e.append("\u001b]2;other\u001b\\y")
        assertEquals("other", t)
        assertEquals("xy", e.line(0))
    }

    @Test
    fun decLineDrawing() {
        val e = emulator()
        e.append("\u001b(0lqk\u001b(Bx")
        assertEquals("┌─┐x", e.line(0))
    }

    @Test
    fun resizeKeepsCursorLine() {
        val e = emulator(cols = 10, rows = 5)
        e.append("1\r\n2\r\n3\r\n4\r\n5")
        e.resize(10, 3)
        assertEquals("3", e.line(0))
        assertEquals("5", e.line(2))
        assertEquals(2, e.cursorY)
        assertEquals(2, e.scrollback.size)
        e.resize(10, 5)
        assertEquals("1", e.line(0))
        assertEquals(4, e.cursorY)
        assertEquals(0, e.scrollback.size)
    }

    @Test
    fun resizeDropsEmptyLinesBelowCursorFirst() {
        val e = emulator(cols = 10, rows = 5)
        e.append("prompt$ ")
        e.resize(8, 2)
        assertEquals("prompt$", e.line(0))
        assertEquals(0, e.cursorY)
        assertEquals(0, e.scrollback.size)
    }

    @Test
    fun getTextJoinsWrappedLines() {
        val e = emulator(cols = 5, rows = 4)
        e.append("abcdefgh\r\nxy")
        assertEquals("abcdefgh\nxy", e.getTranscript())
    }

    @Test
    fun backspaceAfterPendingWrap() {
        val e = emulator(cols = 5)
        e.append("abcde\bX")
        assertEquals("abcXe", e.line(0))
    }

    @Test
    fun tabStops() {
        val e = emulator(cols = 20)
        e.append("a\tb")
        assertEquals(9, e.cursorX)
        assertEquals("a       b", e.line(0))
    }

    @Test
    fun keyEncoder() {
        assertEquals("\u001b[A", KeyEncoder.encodeKey(TermKey.UP, 0, false))
        assertEquals("\u001bOA", KeyEncoder.encodeKey(TermKey.UP, 0, true))
        assertEquals("\u001b[1;5C", KeyEncoder.encodeKey(TermKey.RIGHT, KeyEncoder.MOD_CTRL, false))
        assertEquals("\u0003", KeyEncoder.encodeText("c", ctrl = true, alt = false))
        assertEquals("\u0003", KeyEncoder.encodeText("с", ctrl = true, alt = false)) // cyrillic 'с' is on the 'c' key
        assertEquals("\u001bb", KeyEncoder.encodeText("b", ctrl = false, alt = true))
        assertEquals("ls\r", KeyEncoder.encodeText("ls\n", ctrl = false, alt = false))
    }

    @Test
    fun fuzzNeverThrows() {
        val rnd = java.util.Random(42)
        val finals = "@ABCDEFGHIJKLMPSTXZ`abcdefghlmnqrstu"
        val pieces = listOf(
            "\u001b[", "\u001b]0;", "\u001b(0", "\u001b(B", "\u001b7", "\u001b8", "\u001bM", "\u001bD", "\u001bE",
            "\u001bc", "\u001b#8", "\u001bP", "\u001b\\", "\u0007", "\r", "\n", "\t", "\b", "\u000e", "\u000f",
            "中", "Ж", "é", "\u0301", "😀", "x", " ", ";", ":", "?", ">", "!", "\u001b[?1049h", "\u001b[?1049l",
            "\u001b[?47h", "\u001b[?1047l", "\u001b[?6h", "\u001b[?7l", "\u001b[4h", "\u001b[r",
        )
        repeat(20) { round ->
            val e = TerminalEmulator(1 + rnd.nextInt(120), 1 + rnd.nextInt(50), 50) { }
            repeat(3000) {
                val sb = StringBuilder()
                when (rnd.nextInt(4)) {
                    0 -> {
                        sb.append("\u001b[")
                        if (rnd.nextBoolean()) sb.append("?")
                        repeat(rnd.nextInt(5)) { i ->
                            if (i > 0) sb.append(if (rnd.nextInt(5) == 0) ':' else ';')
                            sb.append(rnd.nextInt(if (rnd.nextBoolean()) 10 else 400))
                        }
                        sb.append(finals[rnd.nextInt(finals.length)])
                    }
                    1 -> repeat(1 + rnd.nextInt(5)) { sb.append(pieces[rnd.nextInt(pieces.size)]) }
                    2 -> repeat(1 + rnd.nextInt(30)) { sb.append(('!' + rnd.nextInt(90))) }
                    else -> {
                        val bytes = ByteArray(1 + rnd.nextInt(20)).also { rnd.nextBytes(it) }
                        e.append(bytes)
                    }
                }
                e.append(sb.toString())
                if (rnd.nextInt(200) == 0) e.resize(1 + rnd.nextInt(150), 1 + rnd.nextInt(60))
                assertTrue("round $round cursorX", e.cursorX in 0 until e.columns)
                assertTrue("round $round cursorY", e.cursorY in 0 until e.rows)
            }
            e.getTranscript()
            e.getText(e.firstAbsoluteRow, 3, e.lastAbsoluteRow, 2)
        }
    }
}
