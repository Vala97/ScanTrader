package com.scantrader.app

import com.scantrader.app.scan.ScanDebug
import com.scantrader.app.scan.ScanDebug.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDebugGateTest {
    @Test
    fun releaseKeepsDecisionWarningsAndErrorsOnly() {
        assertEquals(false, ScanDebug.shouldLog(Level.DEBUG, debugBuild = false, diagnosticLogs = false))
        for (level in listOf(Level.DECISION, Level.WARN, Level.ERROR)) {
            assertEquals(level.name, true, ScanDebug.shouldLog(level, debugBuild = false, diagnosticLogs = false))
        }
    }

    @Test
    fun diagnosticSwitchBringsBackDetailInRelease() {
        for (level in Level.entries) {
            assertEquals(level.name, true, ScanDebug.shouldLog(level, debugBuild = false, diagnosticLogs = true))
        }
    }

    @Test
    fun debugBuildsLogEverythingWithoutTheSwitch() {
        for (level in Level.entries) {
            assertEquals(level.name, true, ScanDebug.shouldLog(level, debugBuild = true, diagnosticLogs = false))
            assertEquals(level.name, true, ScanDebug.shouldLog(level, debugBuild = true, diagnosticLogs = true))
        }
    }
}
