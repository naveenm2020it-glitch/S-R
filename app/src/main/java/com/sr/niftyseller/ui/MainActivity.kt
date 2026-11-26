package com.sr.niftyseller.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.sr.niftyseller.analysis.AnalysisEngine
import com.sr.niftyseller.analysis.AnalysisResult
import com.sr.niftyseller.analysis.EvidenceGrade
import com.sr.niftyseller.analysis.EodSession
import com.sr.niftyseller.analysis.PriceLevel
import com.sr.niftyseller.analysis.SellerSignal
import com.sr.niftyseller.data.NseArchiveDownloader
import com.sr.niftyseller.data.SessionLoader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private val navy = Color.rgb(11, 18, 32)
    private val panel = Color.rgb(20, 30, 48)
    private val textColor = Color.rgb(232, 239, 248)
    private val muted = Color.rgb(158, 174, 194)
    private val teal = Color.rgb(45, 212, 191)
    private val amber = Color.rgb(251, 191, 36)
    private val red = Color.rgb(251, 113, 133)

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var dateButton: Button
    private lateinit var loadButton: Button
    private lateinit var expirySpinner: Spinner
    private lateinit var analyzeButton: Button
    private lateinit var resultsContainer: LinearLayout
    private lateinit var selectedDate: LocalDate
    private var history: List<EodSession> = emptyList()
    private var expiries: List<LocalDate> = emptyList()
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        selectedDate = LocalDate.now()
        buildUi()
        refreshDeleteButton()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(navy); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
            setBackgroundColor(navy)
        }
        scroll.addView(root)
        setContentView(scroll)

        text("S–R", 14, teal, true)
        text("NIFTY seller intelligence", 27, textColor, true).apply { setPadding(0, dp(4), 0, dp(6)) }
        text("Historical NSE EOD data · on-device analysis · no broker or cloud account", 14, muted)
        section("Session & expiry")
        dateButton = button("Analysis date: ${selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}") {
            if (!isLoading) DatePickerDialog(this, { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                dateButton.text = "Analysis date: ${selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
                loadHistory()
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        loadButton = button("Load official session data") { loadHistory() }
        expirySpinner = Spinner(this).apply {
            contentDescription = "NIFTY expiry shared by all validated sessions"
            setBackgroundColor(panel)
            isEnabled = false
        }
        root.addView(expirySpinner, matchWrap())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, matchWrap())
        status = text("Select a trading date, then load its 22-session official report window. Expiries unlock after all required files validate.", 13, muted)
        status.setPadding(0, dp(8), 0, dp(8))
        analyzeButton = button("Analyze selected expiry") { analyze() }.apply { isEnabled = false }
        section("S1 / S2 · R1 / R2")
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsContainer, matchWrap())
        showEmptyResults("No analysis yet", "Load and validate the official session data, choose an expiry, then analyze.")
        section("Multi-strike evidence")
        text("Call/put open interest, one-session change, premium change, volume and repeated EOD writing proxies. Writer identity and intraday absorption are not observable in these public EOD reports.", 13, muted)
        section("Data provenance")
        text("Official NSE archives only. Every candidate session needs F&O Bhavcopy, MII contract master and NSE Indices CSV; original bytes stay in temporary app cache with SHA-256 manifests. Incomplete sessions stop analysis rather than being skipped.", 13, muted)
        val deleteButton = button("Review and delete temporary NSE data") { confirmDelete() }
        deleteButton.tag = "delete"
        section("Important limits")
        text("This is descriptive research, not financial advice or a trading signal. A 22-session sample cannot substantiate predictive ML accuracy. Seller / writer / absorption labels are explicitly indirect proxies, never claims about identified traders.", 12, amber)
    }

    private fun loadHistory() {
        if (isLoading) return
        isLoading = true
        loadButton.isEnabled = false
        loadButton.text = "Loading official session data…"
        progress.visibility = View.VISIBLE
        analyzeButton.isEnabled = false
        expirySpinner.isEnabled = false
        history = emptyList()
        expiries = emptyList()
        resultsContainer.removeAllViews()
        showEmptyResults("Loading session window", "Checking official NSE archives and validating all three reports for each of 22 sessions.")
        status.text = "Preparing official NSE archive checks…"
        Thread {
            try {
                val loaded = SessionLoader(this) { message -> runOnUiThread { status.text = message } }.load(selectedDate)
                runOnUiThread {
                    history = loaded.sessions
                    expiries = loaded.expiries
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, expiries.map(LocalDate::toString)).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    expirySpinner.adapter = adapter
                    expirySpinner.isEnabled = true
                    analyzeButton.isEnabled = true
                    resultsContainer.removeAllViews()
                    showEmptyResults("Ready to analyze", "${loaded.sessions.size} validated sessions are ready. Select one of ${loaded.expiries.size} shared expiries, then tap Analyze selected expiry.")
                    status.text = "Validated ${loaded.sessions.size}/22 unique EOD sessions (${loaded.sessions.first().date} → ${loaded.sessions.last().date}); all 3 official reports per session and matching NIFTY contract IDs checked. ${loaded.reports.size} report files · ${loaded.reports.sumOf { it.bytes } / (1024 * 1024)} MiB in temporary cache."
                    progress.visibility = View.GONE
                    isLoading = false
                    refreshDeleteButton()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    val detail = error.message ?: error.javaClass.simpleName
                    status.text = "Could not load a complete official session window. Check your internet connection and try again. $detail No fallback or substitute dataset was used."
                    resultsContainer.removeAllViews()
                    showEmptyResults("Session data unavailable", "Expiry selection and analysis unlock only after all 22 official sessions validate. Use the button above to retry.")
                    progress.visibility = View.GONE
                    isLoading = false
                    loadButton.isEnabled = true
                    loadButton.text = "Retry loading official data"
                    refreshDeleteButton()
                }

            }
        }.start()
    }

    private fun analyze() {
        val expiry = expiries.getOrNull(expirySpinner.selectedItemPosition) ?: return
        val result = runCatching { AnalysisEngine.analyze(history, selectedDate, expiry) }
            .getOrElse { status.text = "Analysis stopped: ${it.message}"; return }
        render(result)
    }

    private fun render(result: AnalysisResult) {
        resultsContainer.removeAllViews()
        resultsContainer.text("${result.analysisDate} · expiry ${result.expiry}", 14, teal, true)
        resultsContainer.text("${result.learningSessions} learning + ${result.confirmationSessions} confirmation + ${result.targetSessions} target session = ${result.sessions} validated EOD sessions", 13, muted)
        result.levels.forEach { addLevel(it) }
        resultsContainer.text(result.summary, 12, muted)
        result.strikes.sortedBy { kotlin.math.abs(it.strike - history.last().indexClose) }.take(24).forEach { item ->
            val signalColor = when (item.signal) {
                SellerSignal.POSSIBLE_WRITING -> amber
                SellerSignal.POSSIBLE_UNWINDING -> teal
                SellerSignal.MIXED -> muted
                SellerSignal.INSUFFICIENT -> red
            }
            val grade = when (item.grade) {
                EvidenceGrade.TOP_QUARTILE -> "Top-quartile relative proxy"
                EvidenceGrade.MIDDLE_HALF -> "Middle-half relative proxy"
                EvidenceGrade.LOWER_QUARTILE -> "Lower-quartile relative proxy"
                EvidenceGrade.INSUFFICIENT -> "Insufficient"
            }
            card {
                text("${item.strike}  ·  ${item.signal.name.replace('_', ' ')}  ·  $grade", 14, signalColor, true)
                text("CE OI ${format(item.callOi)}  (${signed(item.callOiChange)})  ·  Δ premium ${signedPct(item.callPriceChangePct)}  ·  vol ${format(item.callVolume)}", 12, textColor)
                text("PE OI ${format(item.putOi)}  (${signed(item.putOiChange)})  ·  Δ premium ${signedPct(item.putPriceChangePct)}  ·  vol ${format(item.putVolume)}", 12, textColor)
                text(item.note, 11, muted)
            }
        }
    }

    private fun addLevel(level: PriceLevel) {
        card {
            text("${level.name}  ·  ${level.value?.let(::format) ?: "Not established"}", 18, if (level.value == null) muted else teal, true)
            level.score?.let { text("Cross-sectional evidence rank: ${"%.0f".format(Locale.ROOT, it)}th percentile · not a probability", 12, muted) }
            text(level.explanation, 12, muted)
        }
    }

    private fun confirmDelete() {
        val directory = NseArchiveDownloader.temporaryDirectory(this)
        val files = directory.listFiles().orEmpty()
        if (files.isEmpty()) {
            status.text = "No temporary NSE data remains to delete."
            return
        }
        val size = files.sumOf { it.length() }
        val names = files.take(12).joinToString("\n") { "• ${it.name}" } + if (files.size > 12) "\n… and ${files.size - 12} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Delete downloaded NSE data?")
            .setMessage("This will remove ${files.size} files (${size / (1024 * 1024)} MiB) from the app's temporary cache, including report archives and manifests. It cannot be undone.\n\n$names")
            .setNegativeButton("Keep files", null)
            .setPositiveButton("Delete all") { _, _ ->
                runCatching { NseArchiveDownloader.deleteTemporaryReports(this) }
                    .onSuccess {
                        history = emptyList()
                        expiries = emptyList()
                        expirySpinner.adapter = null
                        expirySpinner.isEnabled = false
                        analyzeButton.isEnabled = false
                        resultsContainer.removeAllViews()
                        status.text = "Temporary report archives and manifests deleted. Analysis cleared."
                    }
                    .onFailure { status.text = "Deletion incomplete: ${it.message}" }
                refreshDeleteButton()
            }.show()
    }

    private fun refreshDeleteButton() {
        (root.findViewWithTag<View>("delete") as? Button)?.isEnabled = !isLoading
    }

    private fun section(label: String) {
        text(label, 17, textColor, true).setPadding(0, dp(24), 0, dp(8))
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(textColor)
        setBackgroundColor(panel)
        contentDescription = label
        setOnClickListener { action() }
        root.addView(this, matchWrap())
    }

    private fun text(label: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
        root.addView(this, matchWrap())
    }

    private fun showEmptyResults(title: String, description: String) {
        card {
            text(title, 15, textColor, true)
            text(description, 13, muted)
        }
    }

    private fun card(content: LinearLayout.() -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(panel)
            content()
        }
        val params = matchWrap().apply { bottomMargin = dp(9) }
        resultsContainer.addView(container, params)
    }

    private fun LinearLayout.text(label: String, size: Int, color: Int, bold: Boolean = false) {
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }, matchWrap())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun format(value: Double) = "%.2f".format(Locale.ROOT, value)
    private fun signed(value: Double) = (if (value >= 0) "+" else "") + format(value)
    private fun signedPct(value: Double?) = value?.let { (if (it >= 0) "+" else "") + format(it) + "%" } ?: "n/a"
}
