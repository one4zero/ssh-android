package com.one4zero.sshterm.terminal

/**
 * Cell style packed into a Long:
 *  bits 0..24  - foreground color
 *  bits 25..49 - background color
 *  bits 50..   - attribute flags
 *
 * A color is either a palette index (0..255), [COLOR_DEFAULT_FG], [COLOR_DEFAULT_BG],
 * or a 24-bit RGB value with [RGB_FLAG] set.
 */
object TextStyle {
    const val COLOR_DEFAULT_FG = 256
    const val COLOR_DEFAULT_BG = 257
    const val RGB_FLAG = 1 shl 24

    private const val COLOR_MASK = (1L shl 25) - 1
    private const val BG_SHIFT = 25

    const val BOLD = 1L shl 50
    const val DIM = 1L shl 51
    const val ITALIC = 1L shl 52
    const val UNDERLINE = 1L shl 53
    const val BLINK = 1L shl 54
    const val INVERSE = 1L shl 55
    const val INVISIBLE = 1L shl 56
    const val STRIKETHROUGH = 1L shl 57

    private const val FLAGS_MASK = -1L shl 50

    val DEFAULT: Long = encode(COLOR_DEFAULT_FG, COLOR_DEFAULT_BG, 0)

    fun encode(fg: Int, bg: Int, flags: Long): Long =
        (fg.toLong() and COLOR_MASK) or ((bg.toLong() and COLOR_MASK) shl BG_SHIFT) or (flags and FLAGS_MASK)

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()
    fun bg(style: Long): Int = ((style shr BG_SHIFT) and COLOR_MASK).toInt()
    fun flags(style: Long): Long = style and FLAGS_MASK

    fun withFg(style: Long, fg: Int): Long = encode(fg, bg(style), flags(style))
    fun withBg(style: Long, bg: Int): Long = encode(fg(style), bg, flags(style))
    fun withFlags(style: Long, flags: Long): Long = encode(fg(style), bg(style), flags)

    fun rgb(r: Int, g: Int, b: Int): Int =
        RGB_FLAG or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)
}
