package com.scantrader.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every log goes through ScanDebug, and no log call touches a credential. */
class ScanDebugSourceGuardTest {
    private val root: File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun sources() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val directLog = Regex("""\bandroid\.util\.Log\b|(?<![\w.])Log\.(v|d|i|w|e|wtf)\(""")

    @Test
    fun onlyScanDebugCallsAndroidLog() {
        val files = sources()
        assertTrue(files.size > 50)
        val offenders = files
            .filter { it.name != "ScanDebug.kt" && directLog.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun noCredentialInScanDebugCalls() {
        val forbidden = listOf("getCardTraderToken", "getTrackerPassword", "Bearer", "secrets.", "Authorization")
        val offenders = mutableListOf<String>()
        for (file in sources()) {
            val text = file.readText()
            for (match in Regex("""ScanDebug\.\w+\(""").findAll(text)) {
                val args = argumentsFrom(text, match.range.last)
                forbidden.filter { args.contains(it) }.forEach {
                    offenders += "${file.name}: $it"
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun guardSeesCallArguments() {
        val text = """ScanDebug.d("token=${'$'}{secrets.getCardTraderToken()}") + other()"""
        val args = argumentsFrom(text, text.indexOf('('))
        assertTrue(args.contains("getCardTraderToken"))
        assertTrue(!args.contains("other"))
    }

    /** Text between the `(` at [open] and its matching `)`. */
    private fun argumentsFrom(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return text.substring(open + 1)
    }
}
