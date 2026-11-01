package com.one4zero.sshterm.terminal

/** Color scheme of the terminal: 16 base colors + derived 256 color palette. */
class TerminalColors private constructor(
    val name: String,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    base16: IntArray,
) {
    val palette = IntArray(256)

    init {
        base16.copyInto(palette)
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            val r = levels[i / 36]
            val g = levels[(i / 6) % 6]
            val b = levels[i % 6]
            palette[16 + i] = argb(r, g, b)
        }
        for (i in 0 until 24) {
            val v = 8 + i * 10
            palette[232 + i] = argb(v, v, v)
        }
    }

    companion object {
        private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        private fun c(hex: Long) = hex.toInt()

        val SCHEMES: List<TerminalColors> = listOf(
            TerminalColors(
                "Тёмная", c(0xFFD4D4D4), c(0xFF161616), c(0xFFE0E0E0),
                intArrayOf(
                    c(0xFF000000), c(0xFFCD3131), c(0xFF0DBC79), c(0xFFE5E510),
                    c(0xFF2472C8), c(0xFFBC3FBC), c(0xFF11A8CD), c(0xFFE5E5E5),
                    c(0xFF666666), c(0xFFF14C4C), c(0xFF23D18B), c(0xFFF5F543),
                    c(0xFF3B8EEA), c(0xFFD670D6), c(0xFF29B8DB), c(0xFFFFFFFF),
                ),
            ),
            TerminalColors(
                "Светлая", c(0xFF2B2B2B), c(0xFFFAFAFA), c(0xFF2B2B2B),
                intArrayOf(
                    c(0xFF000000), c(0xFFC91B00), c(0xFF00A600), c(0xFF9A7B00),
                    c(0xFF0225C7), c(0xFFB13ABD), c(0xFF00939B), c(0xFFBFBFBF),
                    c(0xFF686868), c(0xFFFF6E67), c(0xFF27B336), c(0xFFB59A00),
                    c(0xFF5F79FF), c(0xFFD15FD8), c(0xFF00B4BF), c(0xFF505050),
                ),
            ),
            TerminalColors(
                "Solarized", c(0xFF93A1A1), c(0xFF002B36), c(0xFF93A1A1),
                intArrayOf(
                    c(0xFF073642), c(0xFFDC322F), c(0xFF859900), c(0xFFB58900),
                    c(0xFF268BD2), c(0xFFD33682), c(0xFF2AA198), c(0xFFEEE8D5),
                    c(0xFF586E75), c(0xFFCB4B16), c(0xFF93A1A1), c(0xFFB5A642),
                    c(0xFF839496), c(0xFF6C71C4), c(0xFF33B5A8), c(0xFFFDF6E3),
                ),
            ),
            TerminalColors(
                "Зелёная", c(0xFF33FF66), c(0xFF000000), c(0xFF33FF66),
                intArrayOf(
                    c(0xFF000000), c(0xFFE05050), c(0xFF33FF66), c(0xFFE5E510),
                    c(0xFF4A90E2), c(0xFFBC3FBC), c(0xFF11A8CD), c(0xFFD0D0D0),
                    c(0xFF555555), c(0xFFFF6B6B), c(0xFF7CFF9B), c(0xFFF5F543),
                    c(0xFF6FA8FF), c(0xFFD670D6), c(0xFF29B8DB), c(0xFFFFFFFF),
                ),
            ),
        )

        fun get(index: Int): TerminalColors = SCHEMES.getOrElse(index) { SCHEMES[0] }
    }
}
