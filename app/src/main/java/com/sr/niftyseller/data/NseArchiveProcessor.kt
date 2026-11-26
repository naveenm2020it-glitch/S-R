package com.sr.niftyseller.data

import com.sr.niftyseller.analysis.EodSession
import com.sr.niftyseller.analysis.OptionEod
import com.sr.niftyseller.analysis.OptionSide
import java.io.InputStreamReader
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Strictly validates source date, schema, IDs and legacy MII 1980-epoch/paise units before returning data. */
object NseArchiveProcessor {
    private val dateFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
    )

    data class Contract(val expiry: LocalDate, val strike: Double, val side: OptionSide, val symbol: String)
    data class ParsedSession(val session: EodSession, val contracts: Map<String, Contract>)

    fun parse(reports: List<NseArchiveDownloader.Report>, date: LocalDate, priorContracts: Map<String, Contract>): ParsedSession {
        require(reports.size == 3) { "$date requires all three official reports; got ${reports.size}." }
        val byKind = reports.associateBy { it.kind }
        val bhav = requireNotNull(byKind["fo_bhavcopy"]) { "Missing F&O Bhavcopy for $date." }
        val mii = requireNotNull(byKind["mii_contract"]) { "Missing MII contract master for $date." }
        val index = requireNotNull(byKind["nifty_indices"]) { "Missing NSE Indices CSV for $date." }
        val invalidReports = reports.mapNotNull { report ->
            val failures = buildList {
                if (report.date != date) add("date=${report.date}")
                if (!report.file.isFile) add("file is missing")
                else {
                    if (report.file.length() != report.bytes) add("byte-size mismatch")
                    if (sha256(report.file) != report.sha256) add("SHA-256 mismatch")
                }
                val source = runCatching { URL(report.url) }.getOrNull()
                if (source == null || source.protocol != "https" || !isOfficialHost(source.host)) add("source is not an approved HTTPS archive host")
            }
            failures.takeIf { it.isNotEmpty() }?.let { "${report.kind}: ${it.joinToString()}" }
        }
        require(invalidReports.isEmpty()) { "Report integrity validation failed for $date: ${invalidReports.joinToString("; ")}." }
        val bhavRows = rows(bhav.file).iterator()
        val miiRows = rows(mii.file).iterator()
        val indexRows = rows(index.file).iterator()
        require(bhavRows.hasNext() && miiRows.hasNext() && indexRows.hasNext()) { "One or more official reports contain no headers for $date." }

        val b = Columns(CsvReader.headerIndex(bhavRows.next()), "F&O Bhavcopy", setOf("tradDt", "finInstrmId", "tckrSymb", "xpryDt", "strkPric", "optnTp", "opnPric", "hghPric", "lwPric", "clsPric", "ttlTradgVol", "opnIntrst"))
        val c = Columns(CsvReader.headerIndex(miiRows.next()), "MII contract master", setOf("finInstrmId", "tckrSymb", "xpryDt", "strkPric", "optnTp"))
        val ix = Columns(CsvReader.headerIndex(indexRows.next()), "NSE Indices CSV", setOf("indexName", "indexDate", "openIndexValue", "highIndexValue", "lowIndexValue", "closingIndexValue"))

        val allBhavIds = hashSetOf<String>()
        val optionRows = mutableListOf<List<String>>()
        val observedDates = hashSetOf<LocalDate>()
        var bhavRowCount = 0
        while (bhavRows.hasNext()) {
            val row = bhavRows.next()
            bhavRowCount++
            val rawDate = b.get(row, "tradDt")
            if (rawDate.isNotBlank()) observedDates += parseDate(rawDate) ?: error("Unparseable Bhavcopy trading date '$rawDate'.")
            val id = b.get(row, "finInstrmId").trim()
            if (id.isNotEmpty()) require(allBhavIds.add(id)) { "Duplicate Bhavcopy instrument ID $id for $date." }
            if (b.get(row, "tckrSymb").equals("NIFTY", true) && b.get(row, "optnTp").uppercase(Locale.ROOT) in setOf("CE", "PE")) optionRows += row
        }
        require(bhavRowCount > 0 && observedDates == setOf(date)) { "Bhavcopy trading date does not match selected date $date: $observedDates" }
        require(optionRows.isNotEmpty()) { "No NIFTY option contracts were found in the Bhavcopy for $date." }

        val currentContracts = linkedMapOf<String, Contract>()
        val miiIds = hashSetOf<String>()
        while (miiRows.hasNext()) {
            val row = miiRows.next()
            val id = c.get(row, "finInstrmId").trim()
            if (id.isEmpty()) continue
            require(miiIds.add(id)) { "Duplicate MII instrument ID $id for $date." }
            val symbol = c.get(row, "tckrSymb").trim()
            val type = c.get(row, "optnTp").uppercase(Locale.ROOT)
            if (!symbol.equals("NIFTY", true) || type !in setOf("CE", "PE")) continue
            val expiry = parseMiiExpiry(c.get(row, "xpryDt")) ?: continue
            val strikePaise = c.get(row, "strkPric").toDoubleOrNull() ?: continue
            currentContracts[id] = Contract(expiry, strikePaise / 100.0, OptionSide.valueOf(type), symbol)
        }

        val observedOptions = mutableListOf<OptionEod>()
        optionRows.forEach { row ->
            val id = b.get(row, "finInstrmId").trim()
            val rawStrike = b.get(row, "strkPric").toDoubleOrNull() ?: error("Invalid Bhavcopy strike for $id on $date.")
            val expiry = parseDate(b.get(row, "xpryDt")) ?: error("Invalid Bhavcopy expiry for $id on $date.")
            val side = OptionSide.valueOf(b.get(row, "optnTp").uppercase(Locale.ROOT))
            val contract = currentContracts[id] ?: priorContracts[id]
                ?: error("Unmatched NIFTY option instrument ID $id on $date; no same-day or prior-session MII mapping.")
            require(contract.expiry == expiry && abs(contract.strike - rawStrike) < 1e-8 && contract.side == side) {
                "Contract mapping mismatch for instrument $id on $date (expiry/strike/type)."
            }
            val volume = b.get(row, "ttlTradgVol").toDoubleOrNull() ?: error("Missing/invalid volume for $id on $date.")
            val oi = b.get(row, "opnIntrst").toDoubleOrNull() ?: error("Missing/invalid open interest for $id on $date.")
            require(volume.isFinite() && oi.isFinite() && volume >= 0 && oi >= 0) { "Invalid or negative volume/OI for $id on $date." }
            val open = b.get(row, "opnPric").toDoubleOrNull() ?: error("Invalid open price for $id on $date.")
            val high = b.get(row, "hghPric").toDoubleOrNull() ?: error("Invalid high price for $id on $date.")
            val low = b.get(row, "lwPric").toDoubleOrNull() ?: error("Invalid low price for $id on $date.")
            val close = b.get(row, "clsPric").toDoubleOrNull() ?: error("Invalid close price for $id on $date.")
            require(listOf(open, high, low, close).all { it.isFinite() && it >= 0 }) { "Invalid option prices for $id on $date." }
            if (volume > 0) require(low <= minOf(open, close) && high >= maxOf(open, close) && high >= low) {
                "Invalid traded OHLC bounds for $id on $date."
            }
            observedOptions += OptionEod(date, expiry, rawStrike, side, close, volume, oi)
        }

        var indexRow: List<String>? = null
        var indexMatches = 0
        while (indexRows.hasNext()) {
            val row = indexRows.next()
            if (ix.get(row, "indexName").trim().equals("NIFTY 50", true)) {
                indexRow = row
                indexMatches++
            }
        }
        require(indexMatches == 1) { "Expected exactly one NIFTY 50 row for $date; found $indexMatches." }
        val validatedIndexRow = requireNotNull(indexRow)
        require(parseIndexDate(ix.get(validatedIndexRow, "indexDate")) == date) { "NIFTY 50 index date does not match selected date $date." }
        val open = ix.get(validatedIndexRow, "openIndexValue").toDoubleOrNull() ?: error("Missing NIFTY 50 open on $date.")
        val high = ix.get(validatedIndexRow, "highIndexValue").toDoubleOrNull() ?: error("Missing NIFTY 50 high on $date.")
        val low = ix.get(validatedIndexRow, "lowIndexValue").toDoubleOrNull() ?: error("Missing NIFTY 50 low on $date.")
        val close = ix.get(validatedIndexRow, "closingIndexValue").toDoubleOrNull() ?: error("Missing NIFTY 50 close on $date.")
        require(listOf(open, high, low, close).all { it.isFinite() } && open > 0 && high >= maxOf(open, low, close) && low <= minOf(open, high, close)) { "Invalid NIFTY 50 index OHLC on $date." }
        require(observedOptions.map { Triple(it.expiry, it.strike, it.side) }.distinct().size == observedOptions.size) {
            "Duplicate NIFTY option expiry/strike/type rows for $date."
        }
        return ParsedSession(EodSession(date, close, observedOptions, 3), currentContracts)
    }

    private class Columns(indexes: Map<String, Int>, label: String, required: Set<String>) {
        private val normalizedIndexes = indexes.mapKeys { CsvReader.normalize(it.key) }

        init {
            val absent = required.map(CsvReader::normalize).toSet() - normalizedIndexes.keys
            require(absent.isEmpty()) { "$label schema changed or is invalid; missing normalized columns: ${absent.joinToString()}" }
        }

        fun get(row: List<String>, key: String): String =
            row.getOrNull(normalizedIndexes.getValue(CsvReader.normalize(key))).orEmpty().trim().removeSurrounding("\"")
    }

    private fun rows(file: java.io.File): Sequence<List<String>> = sequence {
        if (file.name.endsWith(".zip")) {
            ZipFile(file).use { zip ->
                val csv = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".csv", true) }.single()
                zip.getInputStream(csv).use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        CsvReader.rows(reader).forEach { row -> yield(row) }
                    }
                }
            }
        } else {
            val stream = file.inputStream().let { if (file.name.endsWith(".gz")) GZIPInputStream(it) else it }
            stream.use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    CsvReader.rows(reader).forEach { row -> yield(row) }
                }
            }
        }
    }

    private fun parseDate(raw: String): LocalDate? {
        val value = raw.trim().removeSurrounding("\"")
        return dateFormatters.firstNotNullOfOrNull { format -> runCatching { LocalDate.parse(value, format) }.getOrNull() }
    }

    private fun parseMiiExpiry(raw: String): LocalDate? {
        val value = raw.trim()
        val epoch = value.toDoubleOrNull()
        if (epoch != null) return runCatching { LocalDate.of(1980, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(epoch.toLong()).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()
        return parseDate(value)
    }

    private fun parseIndexDate(raw: String): LocalDate? = parseDate(raw)

    private fun sha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isOfficialHost(host: String): Boolean = host.lowercase(Locale.ROOT) in setOf(
        "archives.nseindia.com", "nsearchives.nseindia.com"
    )

    private fun abs(value: Double): Double = kotlin.math.abs(value)
}
