package com.example.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.pow

data class GeneratedInstallment(
    val installmentNumber: Int,
    val dueDateIso: String,
    val principalDueMinor: Long,
    val interestDueMinor: Long
) {
    val totalDueMinor: Long get() = principalDueMinor + interestDueMinor
}

object FinancialCalculator {

    private val MC = MathContext(16, RoundingMode.HALF_EVEN)

    fun computeDueDate(startDateIso: String, periodIndexOneBased: Int, frequency: RepaymentFrequency): String {
        return when (frequency) {
            RepaymentFrequency.WEEKLY -> IsoDate.addDays(startDateIso, periodIndexOneBased * 7)
            RepaymentFrequency.BIWEEKLY -> IsoDate.addDays(startDateIso, periodIndexOneBased * 14)
            RepaymentFrequency.SEMI_MONTHLY_15_30 -> computeSemiMonthly1530DueDate(startDateIso, periodIndexOneBased)
            RepaymentFrequency.MONTHLY -> IsoDate.addMonthsClamped(startDateIso, periodIndexOneBased)
        }
    }

    private fun computeSemiMonthly1530DueDate(startDateIso: String, periodIndexOneBased: Int): String {
        val (y, mo, d) = IsoDate.parseYmd(startDateIso) ?: return startDateIso
        val currentSecondSlotDay = minOf(30, IsoDate.daysInMonth(y, mo))

        val startYear: Int
        val startMonth: Int
        val startIsSecondSlot: Boolean
        when {
            d < 15 -> {
                startYear = y
                startMonth = mo
                startIsSecondSlot = false
            }
            d < currentSecondSlotDay -> {
                startYear = y
                startMonth = mo
                startIsSecondSlot = true
            }
            else -> {
                if (mo == 12) {
                    startYear = y + 1
                    startMonth = 1
                } else {
                    startYear = y
                    startMonth = mo + 1
                }
                startIsSecondSlot = false
            }
        }

        val startHalfIndex = (startYear * 12 + (startMonth - 1)) * 2 + (if (startIsSecondSlot) 1 else 0)
        val targetHalfIndex = startHalfIndex + (periodIndexOneBased - 1)
        val targetMonthTotal = targetHalfIndex / 2
        val targetYear = targetMonthTotal / 12
        val targetMonth = (targetMonthTotal % 12) + 1
        val targetIsSecondSlot = (targetHalfIndex % 2) == 1
        val targetDay = if (targetIsSecondSlot) minOf(30, IsoDate.daysInMonth(targetYear, targetMonth)) else 15
        return IsoDate.formatYmd(targetYear, targetMonth, targetDay)
    }

    /**
     * Generates a deterministic repayment schedule using BigDecimal + RoundingMode.HALF_EVEN.
     * Any rounding remainder is absorbed into the final installment so the sum of
     * principalDueMinor equals principalMinor exactly.
     */
    fun generateSchedule(
        principalMinor: Long,
        startDateIso: String,
        termCount: Int,
        frequency: RepaymentFrequency,
        interestMode: InterestMode,
        interestRateBps: Int
    ): List<GeneratedInstallment> {
        if (principalMinor <= 0L || termCount <= 0) return emptyList()

        val p = BigDecimal.valueOf(principalMinor)
        val n = termCount
        val annualRate = BigDecimal.valueOf(interestRateBps.toLong()).divide(BigDecimal.valueOf(10_000L), MC)

        return when (interestMode) {
            InterestMode.ZERO_INTEREST -> {
                val basePrincipal = p.divide(BigDecimal.valueOf(n.toLong()), 0, RoundingMode.HALF_EVEN).toLong()
                var remainingPrincipal = principalMinor
                (1..n).map { idx ->
                    val part = if (idx == n) remainingPrincipal else basePrincipal.coerceAtMost(remainingPrincipal)
                    remainingPrincipal -= part
                    GeneratedInstallment(
                        installmentNumber = idx,
                        dueDateIso = computeDueDate(startDateIso, idx, frequency),
                        principalDueMinor = part,
                        interestDueMinor = 0L
                    )
                }
            }

            InterestMode.SIMPLE_FLAT -> {
                val years = BigDecimal.valueOf(n.toLong())
                    .divide(BigDecimal.valueOf(frequency.periodsPerYear.toLong()), MC)
                val totalInterestMinor = p.multiply(annualRate, MC)
                    .multiply(years, MC)
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .toLong()

                val basePrincipal = p.divide(BigDecimal.valueOf(n.toLong()), 0, RoundingMode.HALF_EVEN).toLong()
                val baseInterest = BigDecimal.valueOf(totalInterestMinor)
                    .divide(BigDecimal.valueOf(n.toLong()), 0, RoundingMode.HALF_EVEN)
                    .toLong()

                var remP = principalMinor
                var remI = totalInterestMinor
                (1..n).map { idx ->
                    val pPart = if (idx == n) remP else basePrincipal.coerceAtMost(remP)
                    val iPart = if (idx == n) remI else baseInterest.coerceAtMost(remI)
                    remP -= pPart
                    remI -= iPart
                    GeneratedInstallment(
                        installmentNumber = idx,
                        dueDateIso = computeDueDate(startDateIso, idx, frequency),
                        principalDueMinor = pPart,
                        interestDueMinor = iPart
                    )
                }
            }

            InterestMode.AMORTIZED_MONTHLY -> {
                val periodRate = annualRate.divide(BigDecimal.valueOf(frequency.periodsPerYear.toLong()), MC)
                if (periodRate.compareTo(BigDecimal.ZERO) == 0) {
                    return generateSchedule(
                        principalMinor = principalMinor,
                        startDateIso = startDateIso,
                        termCount = termCount,
                        frequency = frequency,
                        interestMode = InterestMode.ZERO_INTEREST,
                        interestRateBps = 0
                    )
                }

                val rDouble = periodRate.toDouble()
                val factor = (1.0 + rDouble).pow(n.toDouble())
                val pmtExact = p.multiply(
                    BigDecimal.valueOf(rDouble * factor / (factor - 1.0)),
                    MC
                )
                val pmtMinor = pmtExact.setScale(0, RoundingMode.HALF_EVEN).toLong()

                var balance = principalMinor
                (1..n).map { idx ->
                    val interestMinor = BigDecimal.valueOf(balance)
                        .multiply(periodRate, MC)
                        .setScale(0, RoundingMode.HALF_EVEN)
                        .toLong()
                        .coerceAtLeast(0L)
                    val principalPart = if (idx == n) {
                        balance
                    } else {
                        (pmtMinor - interestMinor).coerceIn(0L, balance)
                    }
                    balance -= principalPart
                    GeneratedInstallment(
                        installmentNumber = idx,
                        dueDateIso = computeDueDate(startDateIso, idx, frequency),
                        principalDueMinor = principalPart,
                        interestDueMinor = interestMinor
                    )
                }
            }
        }
    }

    fun computeLateFee(
        unpaidPrincipalPlusInterestMinor: Long,
        lateFeeBps: Int,
        lateFeeCapMinor: Long
    ): Long {
        if (unpaidPrincipalPlusInterestMinor <= 0L || lateFeeBps <= 0) return 0L
        val rawFee = BigDecimal.valueOf(unpaidPrincipalPlusInterestMinor)
            .multiply(BigDecimal.valueOf(lateFeeBps.toLong()), MC)
            .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.HALF_EVEN)
            .toLong()
            .coerceAtLeast(0L)
        return if (lateFeeCapMinor > 0L) rawFee.coerceAtMost(lateFeeCapMinor) else rawFee
    }
}
