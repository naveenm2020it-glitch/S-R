package com.sr.niftyseller.analysis

import java.time.LocalDate

enum class OptionSide { CE, PE }

data class OptionEod(
    val date: LocalDate,
    val expiry: LocalDate,
    val strike: Double,
    val side: OptionSide,
    val close: Double,
    val volume: Double,
    val openInterest: Double,
)

data class EodSession(
    val date: LocalDate,
    val indexClose: Double,
    val options: List<OptionEod>,
    val sourceCount: Int = 3,
)

enum class EvidenceGrade { TOP_QUARTILE, MIDDLE_HALF, LOWER_QUARTILE, INSUFFICIENT }

enum class SellerSignal { POSSIBLE_WRITING, POSSIBLE_UNWINDING, MIXED, INSUFFICIENT }

data class StrikeEvidence(
    val strike: Double,
    val callOi: Double,
    val putOi: Double,
    val callOiChange: Double,
    val putOiChange: Double,
    val callVolume: Double,
    val putVolume: Double,
    val callPriceChangePct: Double?,
    val putPriceChangePct: Double?,
    val signal: SellerSignal,
    val grade: EvidenceGrade,
    val callLearningProxySessions: Int,
    val putLearningProxySessions: Int,
    val callConfirmationProxySessions: Int,
    val putConfirmationProxySessions: Int,
    val note: String,
)

data class PriceLevel(
    val name: String,
    val value: Double?,
    val score: Double?,
    val evidence: List<StrikeEvidence>,
    val explanation: String,
)

data class AnalysisResult(
    val analysisDate: LocalDate,
    val expiry: LocalDate,
    val sessions: Int,
    val learningSessions: Int,
    val confirmationSessions: Int,
    val targetSessions: Int,
    val levels: List<PriceLevel>,
    val strikes: List<StrikeEvidence>,
    val summary: String,
)
