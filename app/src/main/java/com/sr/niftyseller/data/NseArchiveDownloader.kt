package com.sr.niftyseller.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.math.min

/** Downloads only publicly reachable official NSE archives. No cookies, CAPTCHA bypass or auth workaround. */
class NseArchiveDownloader(context: Context, private val progress: (String) -> Unit = {}) {
    private val root = File(context.cacheDir, "nse-temporary").apply { mkdirs() }
    private val candidates = listOf("https://archives.nseindia.com", "https://nsearchives.nseindia.com")

    data class Report(val kind: String, val date: LocalDate, val file: File, val url: String, val sha256: String, val bytes: Long)
    data class DayResult(val date: LocalDate, val reports: List<Report>, val errors: List<String>) {
        val complete: Boolean get() = reports.size == 3 && errors.isEmpty()
        val noReportsFound: Boolean get() = reports.isEmpty() && errors.all { it.contains("HTTP 404") }
    }

    fun downloadDay(date: LocalDate): DayResult {
        val dmy = date.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val specs = listOf(
            Triple("fo_bhavcopy", "BhavCopy_NSE_FO_0_0_0_${ymd}_F_0000.csv.zip", true),
            Triple("mii_contract", "NSE_FO_contract_${dmy}.csv.gz", false),
            Triple("nifty_indices", "ind_close_all_${dmy}.csv", false),
        )
        val reports = mutableListOf<Report>()
        val errors = mutableListOf<String>()
        specs.forEach { (kind, filename, zip) ->
            try {
                progress("Downloading $kind for $date")
                reports += downloadOne(date, kind, filename, zip)
            } catch (error: Exception) {
                errors += "$kind: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        writeManifest(date, reports, errors)
        return DayResult(date, reports, errors)
    }

    private fun downloadOne(date: LocalDate, kind: String, filename: String, zip: Boolean): Report {
        var lastFailure = "No official archive host responded"
        for (host in candidates) {
            val originalUrl = "$host/content/${if (kind == "nifty_indices") "indices" else "fo"}/$filename"
            var requestUrl = originalUrl
            for (attempt in 0..2) {
                var connection: HttpURLConnection? = null
                val partial = File(root, "$filename.part")
                try {
                    connection = URL(requestUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 45_000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "NIFTY-Seller-Intelligence-Android/1.0 (public NSE archive client)")
                    connection.setRequestProperty("Accept", "application/octet-stream,text/csv,application/zip,application/gzip,*/*")
                    connection.instanceFollowRedirects = false
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val redirect = connection.getHeaderField("Location")
                        val target = redirect?.let { URL(URL(requestUrl), it) }
                        if (target == null || !isOfficialHost(URL(requestUrl).host, target.host)) {
                            throw IllegalStateException("Rejected non-official redirect from $requestUrl")
                        }
                        requestUrl = target.toString()
                        lastFailure = "HTTP $status redirect within the official archive hosts"
                        continue
                    }
                    if (status == 404) {
                        lastFailure = "HTTP 404 from $host"
                        break
                    }
                    if (status == 401 || status == 403) {
                        throw IllegalStateException("HTTP $status: NSE did not permit this public request. No access restriction was bypassed.")
                    }
                    if (status == 429 || status >= 500) {
                        lastFailure = "HTTP $status from $host"
                        if (attempt < 2) Thread.sleep(500L * (attempt + 1))
                        continue
                    }
                    if (status != 200) throw IllegalStateException("HTTP $status from official NSE archive")
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_REPORT_BYTES) throw IllegalStateException("Report exceeds the 80 MB safety limit")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    validateContainer(partial, zip)
                    val destination = File(root, filename)
                    if (destination.exists() && !destination.delete()) throw IllegalStateException("Unable to replace the previous cached report")
                    if (!partial.renameTo(destination)) throw IllegalStateException("Unable to finalize downloaded report")
                    return Report(kind, date, destination, connection.url.toString(), sha256(destination), destination.length())
                } catch (error: Exception) {
                    partial.delete()
                    lastFailure = error.message ?: error.javaClass.simpleName
                    if (lastFailure.contains("HTTP 403") || lastFailure.contains("HTTP 401")) throw error
                    if (attempt < 2) Thread.sleep(400L * (attempt + 1))
                } finally {
                    connection?.disconnect()
                }
            }
            if (lastFailure.contains("HTTP 404")) continue
            if (lastFailure.contains("HTTP 401") || lastFailure.contains("HTTP 403")) break
        }
        throw IllegalStateException(lastFailure)
    }

    private fun validateContainer(file: File, zip: Boolean) {
        val prefix = ByteArray(2)
        file.inputStream().use { it.read(prefix) }
        if (zip) {
            require(prefix.contentEquals(byteArrayOf(0x50, 0x4b))) { "Invalid ZIP signature; response was not stored" }
            ZipFile(file).use { archive ->
                val members = archive.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".csv", true) }.toList()
                require(members.size == 1) { "Expected one CSV member in the Bhavcopy ZIP; found ${members.size}" }
                archive.getInputStream(members.single()).use(::consume)
            }
        } else if (file.name.endsWith(".gz")) {
            require(prefix.contentEquals(byteArrayOf(0x1f, 0x8b.toByte()))) { "Invalid GZIP signature; response was not stored" }
            GZIPInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(consume(input) > 0) { "GZIP report is empty or corrupt" }
            }
        } else {
            require(file.length() > 10 && file.inputStream().use { it.read() >= 0 }) { "CSV report is empty" }
        }
    }

    private fun consume(input: java.io.InputStream): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
        }
    }

    private fun writeManifest(date: LocalDate, reports: List<Report>, errors: List<String>) {
        val manifest = JSONObject()
            .put("generatedAtUtc", java.time.Instant.now().toString())
            .put("tradingDate", date.toString())
            .put("source", "Official NSE/NSE Indices public archives")
            .put("reports", JSONArray().apply {
                reports.forEach { report ->
                    put(JSONObject().put("type", report.kind).put("originalFilename", report.file.name)
                        .put("tradingDate", report.date.toString()).put("sourceUrl", report.url)
                        .put("downloadedAtUtc", java.time.Instant.now().toString()).put("bytes", report.bytes)
                        .put("sha256", report.sha256).put("validationStatus", "CONTAINER_VALIDATED"))
                }
            })
            .put("errors", JSONArray(errors))
        File(root, "manifest-${date}.json").writeText(manifest.toString(2))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isOfficialHost(from: String, to: String): Boolean =
        to == from || to == "archives.nseindia.com" || to == "nsearchives.nseindia.com"

    companion object {
        private const val MAX_REPORT_BYTES = 80L * 1024 * 1024
        fun temporaryDirectory(context: Context): File = File(context.cacheDir, "nse-temporary")
        fun deleteTemporaryReports(context: Context): Boolean {
            val directory = temporaryDirectory(context)
            if (!directory.exists()) return true
            directory.listFiles()?.forEach { file ->
                if (!file.delete()) throw IllegalStateException("Could not delete ${file.name}")
            }
            return directory.delete() || !directory.exists()
        }
    }
}
