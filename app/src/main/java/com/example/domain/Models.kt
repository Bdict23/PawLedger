package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Calendar
import java.util.Currency
import java.util.Locale
import java.util.TimeZone

enum class LoanDirection(val label: String) {
    LENT("Owed to Me"),
    BORROWED("I Owe");

    companion object {
        fun fromRaw(raw: String?): LoanDirection =
            entries.firstOrNull { it.name == raw } ?: LENT
    }
}

enum class RepaymentFrequency(val label: String, val periodsPerYear: Int) {
    WEEKLY("Weekly", 52),
    BIWEEKLY("Bi-Weekly", 26),
    SEMI_MONTHLY_15_30("15-30", 24),
    MONTHLY("Monthly", 12);

    companion object {
        fun fromRaw(raw: String?): RepaymentFrequency =
            entries.firstOrNull { it.name == raw } ?: MONTHLY
    }
}

enum class InterestMode(val label: String) {
    ZERO_INTEREST("Zero Interest"),
    SIMPLE_FLAT("Simple / Flat Fee"),
    AMORTIZED_MONTHLY("Amortized Installments");

    companion object {
        fun fromRaw(raw: String?): InterestMode =
            entries.firstOrNull { it.name == raw } ?: ZERO_INTEREST
    }
}

enum class PaymentAllocationOrder(val label: String) {
    FEES_INTEREST_PRINCIPAL("Fees -> Interest -> Principal"),
    PRINCIPAL_FIRST("Principal First");

    companion object {
        fun fromRaw(raw: String?): PaymentAllocationOrder =
            entries.firstOrNull { it.name == raw } ?: FEES_INTEREST_PRINCIPAL
    }
}

enum class ContractStatus(val label: String) {
    ACTIVE("Active"),
    OVERDUE("Overdue"),
    SETTLED("Settled"),
    DEFAULTED("Defaulted");

    companion object {
        fun fromRaw(raw: String?): ContractStatus =
            entries.firstOrNull { it.name == raw } ?: ACTIVE
    }
}

enum class InstallmentStatus(val label: String) {
    PENDING("Pending"),
    PARTIAL("Partial"),
    PAID("Paid"),
    OVERDUE("Overdue");

    companion object {
        fun fromRaw(raw: String?): InstallmentStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System");

    companion object {
        fun fromRaw(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: DARK
    }
}

enum class TransferMode {
    LOCAL_REBIND,
    BACKUP_BUNDLE
}

enum class TransferStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

enum class DashboardDirectionFilter(val label: String) {
    ALL("All"),
    LENT("Lent"),
    BORROWED("Borrowed")
}

enum class AnalyticsSortOption(val label: String) {
    ON_TIME_SCORE("On-Time Score"),
    RETURN_RATE("Return Rate"),
    TOTAL_VOLUME("Total Volume"),
    NAME("Name")
}

object CurrencyFormatters {
    val supportedCurrencies = listOf("USD", "PHP", "EUR", "GBP", "SGD", "AUD", "CAD", "JPY", "INR")

    fun minorUnitsFractionDigits(currencyCode: String): Int {
        return try {
            Currency.getInstance(currencyCode.uppercase(Locale.US)).defaultFractionDigits.coerceAtLeast(0)
        } catch (_: Exception) {
            2
        }
    }

    fun formatMinor(amountMinor: Long, currencyCode: String): String {
        val code = currencyCode.uppercase(Locale.US)
        val fractionDigits = minorUnitsFractionDigits(code)
        val major = BigDecimal(amountMinor).movePointLeft(fractionDigits)
        return try {
            val fmt = NumberFormat.getCurrencyInstance(Locale.US)
            fmt.currency = Currency.getInstance(code)
            fmt.minimumFractionDigits = fractionDigits
            fmt.maximumFractionDigits = fractionDigits
            fmt.format(major)
        } catch (_: Exception) {
            "$code ${major.setScale(fractionDigits, RoundingMode.HALF_EVEN)}"
        }
    }

    fun parseMajorToMinor(input: String, currencyCode: String): Long? {
        val sanitized = input.trim()
            .replace(",", "")
            .replace(" ", "")
            .trimStart('$', '₱', '€', '£', '¥', '₹')
        if (sanitized.isEmpty()) return null
        return try {
            val bd = BigDecimal(sanitized)
            if (bd <= BigDecimal.ZERO) return null
            val digits = minorUnitsFractionDigits(currencyCode)
            bd.movePointRight(digits).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    fun minorToEditableMajor(amountMinor: Long, currencyCode: String): String {
        val digits = minorUnitsFractionDigits(currencyCode)
        return BigDecimal(amountMinor)
            .movePointLeft(digits)
            .setScale(digits, RoundingMode.HALF_EVEN)
            .stripTrailingZeros()
            .toPlainString()
    }
}

/**
 * Timezone-safe calendar date helper operating exclusively on YYYY-MM-DD strings
 * and UTC midnight epoch days so crossing timezones never shifts a due date.
 */
object IsoDate {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val regex = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun todayIso(): String {
        val local = Calendar.getInstance()
        return formatYmd(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH) + 1,
            local.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun formatYmd(year: Int, month1Based: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month1Based, day)

    fun isValid(iso: String): Boolean = parseYmd(iso) != null

    fun parseYmd(iso: String): Triple<Int, Int, Int>? {
        val m = regex.matchEntire(iso.trim()) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        val cal = Calendar.getInstance(utc).apply {
            isLenient = false
            clear()
            set(y, mo - 1, d, 0, 0, 0)
        }
        return try {
            cal.timeInMillis
            Triple(y, mo, d)
        } catch (_: Exception) {
            null
        }
    }

    fun toEpochDay(iso: String): Long {
        val (y, mo, d) = parseYmd(iso) ?: return 0L
        val cal = Calendar.getInstance(utc).apply {
            clear()
            set(y, mo - 1, d, 0, 0, 0)
        }
        return cal.timeInMillis / 86_400_000L
    }

    fun addDays(iso: String, days: Int): String {
        val (y, mo, d) = parseYmd(iso) ?: return iso
        val cal = Calendar.getInstance(utc).apply {
            clear()
            set(y, mo - 1, d, 0, 0, 0)
            add(Calendar.DAY_OF_MONTH, days)
        }
        return formatYmd(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun addMonthsClamped(iso: String, months: Int): String {
        val (y, mo, d) = parseYmd(iso) ?: return iso
        val cal = Calendar.getInstance(utc).apply {
            clear()
            set(y, mo - 1, 1, 0, 0, 0)
            add(Calendar.MONTH, months)
        }
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val clampedDay = d.coerceAtMost(maxDay)
        return formatYmd(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            clampedDay
        )
    }

    fun toUtcMillis(iso: String): Long? {
        val (y, mo, d) = parseYmd(iso) ?: return null
        val cal = Calendar.getInstance(utc).apply {
            clear()
            set(y, mo - 1, d, 0, 0, 0)
        }
        return cal.timeInMillis
    }

    fun fromUtcMillis(utcMillis: Long): String {
        val cal = Calendar.getInstance(utc).apply {
            timeInMillis = utcMillis
        }
        return formatYmd(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun daysInMonth(year: Int, month1Based: Int): Int {
        val cal = Calendar.getInstance(utc).apply {
            clear()
            set(year, month1Based - 1, 1, 0, 0, 0)
        }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun daysBetween(startIso: String, endIso: String): Long =
        toEpochDay(endIso) - toEpochDay(startIso)

    fun displayFriendly(iso: String): String {
        val (y, mo, d) = parseYmd(iso) ?: return iso
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${months[mo - 1]} $d, $y"
    }
}
