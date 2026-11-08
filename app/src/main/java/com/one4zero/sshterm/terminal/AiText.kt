package com.one4zero.sshterm.terminal

/** Text helpers for exchanging commands and output with AI assistants (chat bots). */
object AiText {
    /**
     * Formats a command and its output as a Markdown code block, ready to paste into an AI chat:
     * ```
     * $ command
     * output
     * ```
     */
    fun codeBlock(command: String?, output: String): String {
        val body = buildString {
            if (!command.isNullOrBlank()) {
                command.trimEnd().lines().forEachIndexed { i, line ->
                    append(if (i == 0) "$ " else "> ").append(line).append('\n')
                }
            }
            append(output.trimEnd('\n', '\r'))
        }.trimEnd()
        val fence = fenceFor(body)
        return "$fence\n$body\n$fence"
    }

    /** A fence that does not occur in the text itself. */
    private fun fenceFor(text: String): String {
        var fence = "```"
        while (text.contains(fence)) fence += "`"
        return fence
    }

    /**
     * Cleans a command copied from an AI answer before it is put into the input line:
     *  - takes the contents of Markdown code blocks (``` or ~~~), dropping the surrounding prose;
     *  - removes inline backticks around a single command (`ls -la`);
     *  - removes shell prompts ("$ ") when every line starts with one.
     * Returns the text unchanged when there is nothing to clean.
     */
    fun cleanCommand(text: String): String {
        var s = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = extractFencedBlocks(s)
        if (blocks != null) s = blocks.joinToString("\n")

        val trimmed = s.trim()
        if (!trimmed.contains('\n') && trimmed.length >= 2 && trimmed.startsWith('`') && trimmed.endsWith('`') &&
            !trimmed.removeSurrounding("`").contains('`')
        ) {
            s = trimmed.removeSurrounding("`")
        }

        val lines = s.trim('\n').lines()
        val nonEmpty = lines.filter { it.isNotBlank() }
        if (nonEmpty.isNotEmpty() && nonEmpty.all { PROMPT.containsMatchIn(it) }) {
            s = lines.joinToString("\n") { PROMPT.replaceFirst(it, "") }
        }
        return s.trim('\n').trimEnd()
    }

    /** Leading "$ " shell prompt (optionally indented). */
    private val PROMPT = Regex("^\\s*\\$ ")

    /** Contents of all fenced code blocks, or null when the text has none. */
    private fun extractFencedBlocks(text: String): List<String>? {
        val lines = text.lines()
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        var fence = ""
        for (line in lines) {
            val t = line.trim()
            if (current == null) {
                val m = FENCE_OPEN.find(t)
                if (m != null) {
                    fence = m.groupValues[1]
                    current = StringBuilder()
                }
            } else {
                if (t.startsWith(fence) && t.trim(fence[0]).isEmpty()) {
                    blocks.add(current.toString().trimEnd('\n'))
                    current = null
                } else {
                    current.append(line).append('\n')
                }
            }
        }
        // an unterminated block (answer copied partially) still counts
        if (current != null) blocks.add(current.toString().trimEnd('\n'))
        return blocks.takeIf { it.isNotEmpty() }
    }

    private val FENCE_OPEN = Regex("^(`{3,}|~{3,})[\\w+.#-]*\\s*$")
}
