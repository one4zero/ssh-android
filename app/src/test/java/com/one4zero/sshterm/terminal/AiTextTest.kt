package com.one4zero.sshterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class AiTextTest {
    @Test
    fun codeBlockWithCommandAndOutput() {
        assertEquals(
            "```\n$ df -h\nFilesystem  Size\n/dev/sda1   20G\n```",
            AiText.codeBlock("df -h", "Filesystem  Size\n/dev/sda1   20G\n\n"),
        )
    }

    @Test
    fun codeBlockWithoutCommand() {
        assertEquals("```\nhello\n```", AiText.codeBlock(null, "hello"))
    }

    @Test
    fun codeBlockUsesLongerFenceWhenOutputHasBackticks() {
        assertEquals("````\n$ cat x.md\n```\ncode\n```\n````", AiText.codeBlock("cat x.md", "```\ncode\n```"))
    }

    @Test
    fun multiLineCommandUsesContinuationPrompt() {
        assertEquals("```\n$ for i in 1 2; do\n>   echo \$i\n> done\n1\n2\n```", AiText.codeBlock("for i in 1 2; do\n  echo \$i\ndone", "1\n2"))
    }

    @Test
    fun cleanTakesFencedBlockOnly() {
        val answer = "Run this command:\n\n```bash\nsudo apt update\nsudo apt install -y nginx\n```\n\nThen check the status."
        assertEquals("sudo apt update\nsudo apt install -y nginx", AiText.cleanCommand(answer))
    }

    @Test
    fun cleanJoinsSeveralBlocks() {
        val answer = "First:\n```\nls\n```\nthen:\n~~~sh\npwd\n~~~"
        assertEquals("ls\npwd", AiText.cleanCommand(answer))
    }

    @Test
    fun cleanRemovesPrompts() {
        assertEquals("cd /var/log\ntail -n 20 syslog", AiText.cleanCommand("$ cd /var/log\n$ tail -n 20 syslog"))
        assertEquals("ls -la", AiText.cleanCommand("```console\n$ ls -la\n```"))
    }

    @Test
    fun cleanKeepsOutputLinesAndPromptsWhenMixed() {
        val mixed = "$ uname -r\n6.1.0"
        assertEquals(mixed, AiText.cleanCommand(mixed))
    }

    @Test
    fun cleanRemovesInlineBackticks() {
        assertEquals("systemctl restart nginx", AiText.cleanCommand("`systemctl restart nginx`"))
    }

    @Test
    fun cleanLeavesPlainCommandsAlone() {
        assertEquals("echo \$HOME | grep root", AiText.cleanCommand("echo \$HOME | grep root\n"))
        assertEquals("# comment\nls", AiText.cleanCommand("# comment\nls"))
        assertEquals("echo `date`", AiText.cleanCommand("echo `date`"))
    }

    @Test
    fun cleanHandlesUnterminatedBlockAndCrLf() {
        assertEquals("docker ps\ndocker logs app", AiText.cleanCommand("```\r\ndocker ps\r\ndocker logs app\r\n"))
    }
}
