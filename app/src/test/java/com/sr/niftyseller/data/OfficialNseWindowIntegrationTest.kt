package com.sr.niftyseller.data

import com.sr.niftyseller.analysis.AnalysisEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Runs only when pointed at an independently downloaded official NSE archive manifest. */
class OfficialNseWindowIntegrationTest {
    @Test
    fun parsesAndAnalyzesTheRealOfficialWindow() {
        val manifestPath = System.getenv("NSE_OFFICIAL_WINDOW_MANIFEST")
        assumeTrue("Set NSE_OFFICIAL_WINDOW_MANIFEST to run this real-data integration test", !manifestPath.isNullOrBlank())

        val manifestFile = File(requireNotNull(manifestPath)).canonicalFile
        assumeTrue("Official audit manifest is not present", manifestFile.isFile)
        val manifest = JSONObject(manifestFile.readText())
        assertEquals("COMPLETE_SOURCE_AND_CONTRACT_VALIDATION", manifest.getString("status"))
        val analysisDate = LocalDate.parse(manifest.getString("analysis_date"))
        val dataRoot = requireNotNull(manifestFile.parentFile?.parentFile?.parentFile)
        val sourceFiles = manifest.getJSONArray("source_files")
        val reportGroups = (0 until sourceFiles.length()).map { sourceFiles.getJSONObject(it) }
            .groupBy { it.getString("trading_date") }

        val parsedSessions = mutableListOf<NseArchiveProcessor.ParsedSession>()
        var priorContracts = emptyMap<String, NseArchiveProcessor.Contract>()
        reportGroups.keys.sorted().forEach { rawDate ->
            val date = LocalDate.parse(rawDate)
            val reports = reportGroups.getValue(rawDate).map { item ->
                NseArchiveDownloader.Report(
                    kind = item.getString("type"),
                    date = date,
                    file = File(dataRoot, item.getString("path")).canonicalFile,
                    url = item.getString("source_url"),
                    sha256 = item.getString("sha256"),
                    bytes = item.getLong("bytes"),
                )
            }
            val parsed = NseArchiveProcessor.parse(reports, date, priorContracts)
            parsedSessions += parsed
            priorContracts = parsed.contracts
        }

        assertEquals(22, parsedSessions.size)
        assertEquals(analysisDate, parsedSessions.last().session.date)
        val availableEverySession = parsedSessions
            .map { item -> item.session.options.map { it.expiry }.toSet() }
            .reduce(Set<LocalDate>::intersect)
            .filter { it >= analysisDate }
            .sorted()
        assertTrue("No single expiry is available with validated contracts across all 22 dates", availableEverySession.isNotEmpty())
        val selectedExpiry = availableEverySession.first()
        val analysisSessions = parsedSessions.map { parsed ->
            parsed.session.copy(options = parsed.session.options.filter { it.expiry == selectedExpiry })
        }
        val result = AnalysisEngine.analyze(analysisSessions, analysisDate, selectedExpiry)
        assertEquals(22, result.sessions)
        assertEquals(17, result.learningSessions)
        assertEquals(4, result.confirmationSessions)
        assertEquals(1, result.targetSessions)
    }
}
