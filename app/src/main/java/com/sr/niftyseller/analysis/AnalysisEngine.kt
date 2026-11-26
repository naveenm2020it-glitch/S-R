package com.sr.niftyseller.analysis

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** EOD descriptive analytics only; OI and price behavior are proxies, never proof of trader identity. */
object AnalysisEngine {
    private const val REQUIRED_SESSIONS = 22

    fun analyze(sessions: List<EodSession>, analysisDate: LocalDate, expiry: LocalDate): AnalysisResult {
        require(sessions.size == REQUIRED_SESSIONS) { "Analysis requires 22 complete NSE sessions; found ${sessions.size}." }
        require(sessions.map { it.date }.distinct().size == REQUIRED_SESSIONS) { "Duplicate trading sessions are not allowed." }
        require(sessions.map { it.date } == sessions.map { it.date }.sorted()) { "Sessions must be in chronological order." }
        require(sessions.last().date == analysisDate) { "The selected analysis date is not the final validated session." }
        require(sessions.all { it.sourceCount == 3 && it.indexClose.isFinite() && it.indexClose > 0 }) {
            "Every session requires all three validated official reports and a valid NIFTY 50 close."
        }
        require(sessions.all { session -> session.options.isNotEmpty() && session.options.all { it.date == session.date && it.expiry == expiry } }) {
            "An official option chain for the same selected expiry is required on every session."
        }

        val byDay = sessions.map { session -> session.options.associateBy { it.strike to it.side } }
        val strikes = sessions.flatMap { it.options.map(OptionEod::strike) }.distinct().sorted()
        val targetIndex = REQUIRED_SESSIONS - 1
        val provisionalEvidence = strikes.map { strike ->
            val call = byDay[targetIndex][strike to OptionSide.CE]
            val put = byDay[targetIndex][strike to OptionSide.PE]
            val prevCall = byDay[targetIndex - 1][strike to OptionSide.CE]
            val prevPut = byDay[targetIndex - 1][strike to OptionSide.PE]
            val callChange = delta(call?.openInterest, prevCall?.openInterest)
            val putChange = delta(put?.openInterest, prevPut?.openInterest)
            val callPrice = pctChange(call?.close, prevCall?.close)
            val putPrice = pctChange(put?.close, prevPut?.close)
            val callWriting = isPossibleWriting(callChange, callPrice)
            val putWriting = isPossibleWriting(putChange, putPrice)
            val callUnwinding = isPossibleUnwinding(callChange, callPrice)
            val putUnwinding = isPossibleUnwinding(putChange, putPrice)
            val signal = when {
                callWriting || putWriting -> SellerSignal.POSSIBLE_WRITING
                callUnwinding || putUnwinding -> SellerSignal.POSSIBLE_UNWINDING
                call == null || put == null -> SellerSignal.INSUFFICIENT
                else -> SellerSignal.MIXED
            }
            val callRepeated = (0 until 17).count { index ->
                val current = byDay[index][strike to OptionSide.CE]
                val previous = if (index > 0) byDay[index - 1][strike to OptionSide.CE] else null
                isPossibleWriting(delta(current?.openInterest, previous?.openInterest), pctChange(current?.close, previous?.close))
            }
            val putRepeated = (0 until 17).count { index ->
                val current = byDay[index][strike to OptionSide.PE]
                val previous = if (index > 0) byDay[index - 1][strike to OptionSide.PE] else null
                isPossibleWriting(delta(current?.openInterest, previous?.openInterest), pctChange(current?.close, previous?.close))
            }
            val callConfirmation = (17..20).count { index ->
                val current = byDay[index][strike to OptionSide.CE]
                val previous = byDay[index - 1][strike to OptionSide.CE]
                isPossibleWriting(delta(current?.openInterest, previous?.openInterest), pctChange(current?.close, previous?.close))
            }
            val putConfirmation = (17..20).count { index ->
                val current = byDay[index][strike to OptionSide.PE]
                val previous = byDay[index - 1][strike to OptionSide.PE]
                isPossibleWriting(delta(current?.openInterest, previous?.openInterest), pctChange(current?.close, previous?.close))
            }
            val note = buildString {
                append("OI change is compared with the prior EOD. ")
                if (callWriting) append("Call OI rose while its premium fell: possible call-writer absorption-style proxy. ")
                if (putWriting) append("Put OI rose while its premium fell: possible put-writer absorption-style proxy. ")
                if (callUnwinding || putUnwinding) append("OI fell as premium rose: possible writer position-reduction/distribution proxy. ")
                append("Call pattern: $callRepeated/17 learning and $callConfirmation/4 confirmation sessions; put pattern: $putRepeated/17 and $putConfirmation/4. EOD data cannot identify writers or prove intraday absorption/distribution.")
            }
            StrikeEvidence(
                strike = strike,
                callOi = call?.openInterest ?: 0.0,
                putOi = put?.openInterest ?: 0.0,
                callOiChange = callChange ?: 0.0,
                putOiChange = putChange ?: 0.0,
                callVolume = call?.volume ?: 0.0,
                putVolume = put?.volume ?: 0.0,
                callPriceChangePct = callPrice,
                putPriceChangePct = putPrice,
                signal = signal,
                grade = EvidenceGrade.INSUFFICIENT,
                callLearningProxySessions = callRepeated,
                putLearningProxySessions = putRepeated,
                callConfirmationProxySessions = callConfirmation,
                putConfirmationProxySessions = putConfirmation,
                note = note,
            )
        }
        val writingProxies = provisionalEvidence.filter { it.signal == SellerSignal.POSSIBLE_WRITING }
        fun patternScore(item: StrikeEvidence): Double = max(
            (item.callLearningProxySessions / 17.0 + item.callConfirmationProxySessions / 4.0) / 2.0,
            (item.putLearningProxySessions / 17.0 + item.putConfirmationProxySessions / 4.0) / 2.0,
        )
        val patternScores = writingProxies.map(::patternScore)
        val lowerQuartile = quantile(patternScores, 0.25)
        val upperQuartile = quantile(patternScores, 0.75)
        val evidence = provisionalEvidence.map { item ->
            val grade = if (item.signal != SellerSignal.POSSIBLE_WRITING || patternScores.isEmpty()) {
                EvidenceGrade.INSUFFICIENT
            } else {
                when {
                    patternScore(item) > upperQuartile -> EvidenceGrade.TOP_QUARTILE
                    patternScore(item) < lowerQuartile -> EvidenceGrade.LOWER_QUARTILE
                    else -> EvidenceGrade.MIDDLE_HALF
                }
            }
            item.copy(grade = grade)
        }

        val spot = sessions.last().indexClose
        val levels = listOf(
            level("S1", "Support", evidence, OptionSide.PE, 1, spot),
            level("S2", "Support", evidence, OptionSide.PE, 2, spot),
            level("R1", "Resistance", evidence, OptionSide.CE, 1, spot),
            level("R2", "Resistance", evidence, OptionSide.CE, 2, spot),
        )
        return AnalysisResult(
            analysisDate = analysisDate,
            expiry = expiry,
            sessions = sessions.size,
            learningSessions = 17,
            confirmationSessions = 4,
            targetSessions = 1,
            levels = levels,
            strikes = evidence,
            summary = "Descriptive historical EOD analysis only. 17 sessions establish recurring OI/premium proxies, 4 sessions confirm or weaken them, and the final session is the target. OI alone does not prove writing, absorption or distribution.",
        )
    }

    private fun level(name: String, sideLabel: String, evidence: List<StrikeEvidence>, side: OptionSide, rank: Int, spot: Double): PriceLevel {
        val candidates = evidence.filter { item ->
            if (side == OptionSide.PE && item.strike > spot) return@filter false
            if (side == OptionSide.CE && item.strike < spot) return@filter false
            val oi = if (side == OptionSide.CE) item.callOi else item.putOi
            val change = if (side == OptionSide.CE) item.callOiChange else item.putOiChange
            val priceChange = if (side == OptionSide.CE) item.callPriceChangePct else item.putPriceChangePct
            oi > 0 && change > 0 && priceChange != null && priceChange < 0
        }
        if (candidates.isEmpty()) return PriceLevel(
            name, null, null, emptyList(), "Insufficient validated ${sideLabel.lowercase()}-side seller-proxy evidence to support this level; no value was invented."
        )
        val scoreByStrike = candidates.associateWith { candidate -> compositeLevelScore(evidence, candidate, side) }
        val selected = candidates.sortedByDescending { scoreByStrike.getValue(it) }
        val chosen = selected.getOrNull(rank - 1) ?: return PriceLevel(
            name, null, null, emptyList(), "Insufficient distinct validated evidence for $name; no value was invented."
        )
        val matched = selected.filter { abs(it.strike - chosen.strike) <= nearestGap(selected) }
        val score = rankPercentile(scoreByStrike.values.toList(), scoreByStrike.getValue(chosen)) * 100.0
        val sideName = if (side == OptionSide.PE) "Put" else "Call"
        return PriceLevel(
            name, chosen.strike, score, matched,
            "$sideLabel candidate at ${chosen.strike}: $sideName OI increased while its premium declined. The rank gives equal weight to current OI, OI change, volume, premium change, 17-session repetition and 4-session confirmation. It is a cross-sectional percentile, not a probability or forecast."
        )
    }

    private fun compositeLevelScore(evidence: List<StrikeEvidence>, item: StrikeEvidence, side: OptionSide): Double {
        val oi = if (side == OptionSide.CE) item.callOi else item.putOi
        val oiValues = evidence.map { if (side == OptionSide.CE) it.callOi else it.putOi }
        val change = if (side == OptionSide.CE) item.callOiChange else item.putOiChange
        val changeValues = evidence.map { if (side == OptionSide.CE) it.callOiChange else it.putOiChange }
        val volume = if (side == OptionSide.CE) item.callVolume else item.putVolume
        val volumeValues = evidence.map { if (side == OptionSide.CE) it.callVolume else it.putVolume }
        val priceChange = if (side == OptionSide.CE) item.callPriceChangePct else item.putPriceChangePct
        val priceValues = evidence.mapNotNull { if (side == OptionSide.CE) it.callPriceChangePct else it.putPriceChangePct }.map { -it }
        val learningCount = if (side == OptionSide.CE) item.callLearningProxySessions else item.putLearningProxySessions
        val confirmationCount = if (side == OptionSide.CE) item.callConfirmationProxySessions else item.putConfirmationProxySessions
        val writingRepetition = evidence.map { if (side == OptionSide.CE) it.callLearningProxySessions.toDouble() else it.putLearningProxySessions.toDouble() }
        val confirmation = evidence.map { if (side == OptionSide.CE) it.callConfirmationProxySessions.toDouble() else it.putConfirmationProxySessions.toDouble() }
        val features = listOf(
            rankPercentile(oiValues, oi),
            rankPercentile(changeValues, change),
            rankPercentile(volumeValues, volume),
            rankPercentile(priceValues, -(priceChange ?: 0.0)),
            rankPercentile(writingRepetition, learningCount.toDouble()),
            rankPercentile(confirmation, confirmationCount.toDouble()),
        )
        return features.average()
    }

    private fun nearestGap(items: List<StrikeEvidence>): Double {
        val gaps = items.map { it.strike }.distinct().sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }
        return gaps.minOrNull() ?: 0.0
    }

    private fun rankPercentile(values: List<Double>, value: Double): Double {
        val valid = values.filter(Double::isFinite)
        if (valid.isEmpty() || !value.isFinite()) return 0.0
        val below = valid.count { it < value }
        val tied = valid.count { it == value }
        return (below + tied / 2.0) / valid.size
    }

    private fun quantile(values: List<Double>, probability: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.filter(Double::isFinite).sorted()
        if (sorted.isEmpty()) return Double.NaN
        val position = (sorted.lastIndex * probability.coerceIn(0.0, 1.0))
        val lowerIndex = position.toInt()
        val upperIndex = kotlin.math.ceil(position).toInt()
        val fraction = position - lowerIndex
        return sorted[lowerIndex] * (1.0 - fraction) + sorted[upperIndex] * fraction
    }

    private fun delta(now: Double?, previous: Double?): Double? = if (now == null || previous == null) null else now - previous

    private fun pctChange(now: Double?, previous: Double?): Double? =
        if (now == null || previous == null || previous <= 0.0) null else (now - previous) / previous * 100.0

    private fun isPossibleWriting(oiChange: Double?, premiumChange: Double?): Boolean =
        oiChange != null && premiumChange != null && oiChange > 0.0 && premiumChange < 0.0

    private fun isPossibleUnwinding(oiChange: Double?, premiumChange: Double?): Boolean =
        oiChange != null && premiumChange != null && oiChange < 0.0 && premiumChange > 0.0
}
