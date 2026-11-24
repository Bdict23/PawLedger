package com.example

import com.example.domain.FinancialCalculator
import com.example.domain.InterestMode
import com.example.domain.IsoDate
import com.example.domain.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialCalculatorTest {
    @Test
    fun zeroInterestScheduleSumsExactPrincipal() {
        val schedule = FinancialCalculator.generateSchedule(
            principalMinor = 100_00L, // $100.00
            startDateIso = "2026-01-31",
            termCount = 3,
            frequency = RepaymentFrequency.MONTHLY,
            interestMode = InterestMode.ZERO_INTEREST,
            interestRateBps = 0
        )
        assertEquals(3, schedule.size)
        assertEquals(100_00L, schedule.sumOf { it.principalDueMinor })
        assertEquals(0L, schedule.sumOf { it.interestDueMinor })
        // Month-end clamping: Jan 31 -> Feb 28 (2026 non-leap), Mar 31, Apr 30
        assertEquals("2026-02-28", schedule[0].dueDateIso)
        assertEquals("2026-03-31", schedule[1].dueDateIso)
        assertEquals("2026-04-30", schedule[2].dueDateIso)
    }

    @Test
    fun amortizedScheduleSumsExactPrincipal() {
        val schedule = FinancialCalculator.generateSchedule(
            principalMinor = 120_000L, // $1,200.00
            startDateIso = "2026-11-23",
            termCount = 12,
            frequency = RepaymentFrequency.MONTHLY,
            interestMode = InterestMode.AMORTIZED_MONTHLY,
            interestRateBps = 1200 // 12% APR
        )
        assertEquals(12, schedule.size)
        assertEquals(120_000L, schedule.sumOf { it.principalDueMinor })
        assertTrue(schedule.sumOf { it.interestDueMinor } > 0L)
    }

    @Test
    fun lateFeeRespectsCap() {
        val fee = FinancialCalculator.computeLateFee(
            unpaidPrincipalPlusInterestMinor = 100_000L,
            lateFeeBps = 500, // 5% = 5,000
            lateFeeCapMinor = 2_500L
        )
        assertEquals(2_500L, fee)
    }

    @Test
    fun isoDateDaysBetweenIsExact() {
        assertEquals(3L, IsoDate.daysBetween("2026-11-23", "2026-11-26"))
        assertEquals(-2L, IsoDate.daysBetween("2026-11-25", "2026-11-23"))
    }

    @Test
    fun semiMonthly1530ScheduleGenerates15thAnd30thDatesWithFebClamping() {
        // Start mid-month after 15th -> 1st due Nov 30, then Dec 15, Dec 30, Jan 15
        val s1 = FinancialCalculator.generateSchedule(
            principalMinor = 100_00L,
            startDateIso = "2026-11-23",
            termCount = 4,
            frequency = RepaymentFrequency.SEMI_MONTHLY_15_30,
            interestMode = InterestMode.ZERO_INTEREST,
            interestRateBps = 0
        )
        assertEquals(listOf("2026-11-30", "2026-12-15", "2026-12-30", "2027-01-15"), s1.map { it.dueDateIso })
        assertEquals(100_00L, s1.sumOf { it.principalDueMinor })

        // Start on Jan 31 -> 1st due Feb 15, Feb 28 (non-leap), Mar 15, Mar 30
        val s2 = FinancialCalculator.generateSchedule(
            principalMinor = 200_00L,
            startDateIso = "2026-01-31",
            termCount = 4,
            frequency = RepaymentFrequency.SEMI_MONTHLY_15_30,
            interestMode = InterestMode.SIMPLE_FLAT,
            interestRateBps = 1200
        )
        assertEquals(listOf("2026-02-15", "2026-02-28", "2026-03-15", "2026-03-30"), s2.map { it.dueDateIso })
        assertEquals(200_00L, s2.sumOf { it.principalDueMinor })
    }

    @Test
    fun isoDateUtcMillisRoundtripIsExact() {
        val date = "2026-11-23"
        val millis = IsoDate.toUtcMillis(date)!!
        assertEquals(date, IsoDate.fromUtcMillis(millis))
    }
}
