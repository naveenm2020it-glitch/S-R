package com.sr.niftyseller.data

import android.content.Context
import com.sr.niftyseller.analysis.EodSession
import java.time.LocalDate

class SessionLoader(context: Context, private val status: (String) -> Unit = {}) {
    data class LoadedHistory(val analysisDate: LocalDate, val sessions: List<EodSession>, val expiries: List<LocalDate>, val reports: List<NseArchiveDownloader.Report>)

    private val downloader = NseArchiveDownloader(context) { status(it) }

    fun load(analysisDate: LocalDate): LoadedHistory {
        val downloadedDays = mutableListOf<NseArchiveDownloader.DayResult>()
        var candidateDate = analysisDate
        var examinedCalendarDays = 0
        while (downloadedDays.size < REQUIRED_SESSIONS && examinedCalendarDays < MAX_LOOKBACK_DAYS) {
            status("Checking official report availability for $candidateDate (${downloadedDays.size}/$REQUIRED_SESSIONS valid sessions)")
            val result = downloader.downloadDay(candidateDate)
            when {
                result.complete -> downloadedDays += result
                result.noReportsFound && candidateDate != analysisDate -> Unit
                result.noReportsFound -> throw IllegalStateException("No official NSE reports are available for the selected analysis date $analysisDate. Choose a date with a validated NSE trading session; no fallback date was selected.")
                else -> throw IllegalStateException("Incomplete or invalid reports for $candidateDate: ${result.errors.joinToString("; ")}. The session was not silently skipped.")
            }
            candidateDate = candidateDate.minusDays(1)
            examinedCalendarDays++
        }
        if (downloadedDays.size != REQUIRED_SESSIONS) {
            throw IllegalStateException("Only ${downloadedDays.size} complete official sessions were found in $MAX_LOOKBACK_DAYS calendar days. Analysis requires exactly 22.")
        }

        val chronological = downloadedDays.asReversed()
        val sessions = mutableListOf<EodSession>()
        var prior = emptyMap<String, NseArchiveProcessor.Contract>()
        chronological.forEach { day ->
            val parsed = NseArchiveProcessor.parse(day.reports, day.date, prior)
            sessions += parsed.session
            prior = parsed.contracts
        }
        if (sessions.last().date != analysisDate) throw IllegalStateException("Selected date is not the latest validated session.")
        val expiries = sessions.map { session -> session.options.map { it.expiry }.toSet() }
            .reduce { common, current -> common intersect current }
            .filter { expiry -> sessions.all { session -> session.options.any { it.expiry == expiry } } }
            .sorted()
        if (expiries.isEmpty()) throw IllegalStateException("No single NIFTY expiry has validated contracts across all 22 sessions.")
        return LoadedHistory(analysisDate, sessions, expiries, chronological.flatMap { it.reports })
    }

    companion object {
        private const val REQUIRED_SESSIONS = 22
        private const val MAX_LOOKBACK_DAYS = 90
    }
}
