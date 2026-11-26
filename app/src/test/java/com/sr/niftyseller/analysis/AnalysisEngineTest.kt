package com.sr.niftyseller.analysis

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** These tests exercise refusal/structural paths only; they do not invent or simulate market observations. */
class AnalysisEngineTest {
    private val expiry = LocalDate.parse("2026-12-29")

    private fun emptySessions(count: Int): List<EodSession> = (0 until count).map { index ->
        EodSession(LocalDate.parse("2026-10-21").plusDays(index.toLong()), 0.0, emptyList())
    }

    @Test fun `refuses fewer than 22 sessions without substituting another date`() {
        val sessions = emptySessions(21)
        val error = runCatching { AnalysisEngine.analyze(sessions, sessions.last().date, expiry) }.exceptionOrNull()
        assertEquals("Analysis requires 22 complete NSE sessions; found 21.", error?.message)
    }

    @Test fun `refuses duplicate session dates`() {
        val sessions = emptySessions(22).toMutableList()
        sessions[1] = sessions[0]
        val error = runCatching { AnalysisEngine.analyze(sessions, sessions.last().date, expiry) }.exceptionOrNull()
        assertEquals("Duplicate trading sessions are not allowed.", error?.message)
    }

    @Test fun `refuses a selected analysis date that is not the final session`() {
        val sessions = emptySessions(22)
        val error = runCatching { AnalysisEngine.analyze(sessions, sessions.first().date, expiry) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("not the final validated session"))
    }
}
