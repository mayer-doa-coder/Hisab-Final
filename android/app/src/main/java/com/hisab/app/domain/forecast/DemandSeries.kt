package com.hisab.app.domain.forecast

import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.sum
import java.time.LocalDate
import java.time.ZoneId

// The forecasting data pipeline (Step 84).
//
// Completed sales in, one row per calendar day out: (date, product_id,
// quantity_sold). Nothing here touches the screen, the database or the
// network — `data/forecast/DemandRepository.kt` reads the rows and hands them
// to these functions, which is what lets every rule below be tested on a
// laptop against a sale history written by hand.
//
// Four rules decide everything in this file, and each one is a decision that
// could have gone the other way:
//
//  1. A day with no sale is a real observation of zero, not a missing row.
//     Every forecasting method from Step 85 onward counts days, so a series
//     with the quiet days left out would tell EWMA that demand arrives every
//     day and tell Croston that it never pauses. The series is therefore
//     gapless by construction — `DemandSeries` refuses to exist otherwise.
//
//  2. Order is never lost. The days are a list in calendar order, oldest
//     first, and nothing here sorts or shuffles after that. A forecast built
//     from shuffled history is not a forecast.
//
//  3. A reversed sale is demand that never happened, and it is subtracted
//     from the day the sale happened — not from the day it was undone. A
//     reversal is stored as a second, opposite sale (D033), so its negative
//     line would otherwise land days later and invent a dip in demand that no
//     shopkeeper would recognise.
//
//  4. A product that is gone stops having demand days. Padding zeros onto the
//     end of a withdrawn product's series is not "no demand" — it is the
//     absence of an offer, and it drags every method's answer toward zero.

/**
 * What one product sold on one day, and whether the ledger said it was out of
 * stock at any point that day.
 *
 * [stockOut] matters because a zero on a stockout day is not a zero of
 * demand: the shop could not have sold it. `docs/RESEARCH_PLAN.md` requires
 * the mark to be calculated from stock movements rather than typed, and
 * requires forecast error to be reported both with and without these days
 * (Step 88). It stays false until [markStockOutDays] works it out.
 */
data class DailyDemand(
    val date: LocalDate,
    val quantitySold: Quantity,
    val stockOut: Boolean = false,
)

/**
 * One product's demand, one row per calendar day, oldest day first and with no
 * gaps.
 *
 * The `init` block is the contract: anyone holding a `DemandSeries` holds a
 * gapless, chronological, non-negative series. That is what lets the
 * forecasting methods count days instead of reading dates.
 */
data class DemandSeries(
    val productId: EntityId,
    val days: List<DailyDemand>,
) {
    init {
        require(days.zipWithNext().all { (earlier, later) -> later.date == earlier.date.plusDays(1) }) {
            "A demand series has one row per calendar day, in order, with no gaps — " +
                "fill quiet days with zero instead of leaving them out."
        }
        require(days.all { it.quantitySold.scaledUnits >= 0 }) {
            "A day cannot sell a negative amount. A reversal is netted against the day the sale happened."
        }
    }

    val dayCount: Int get() = days.size

    val firstDay: LocalDate? get() = days.firstOrNull()?.date

    val lastDay: LocalDate? get() = days.lastOrNull()?.date

    val totalSold: Quantity get() = days.map { it.quantitySold }.sum()

    /** How many days the ledger read zero or less — see [markStockOutDays]. */
    val stockOutDays: Int get() = days.count { it.stockOut }

    /**
     * Whether this series is long enough to appear in the research results at
     * all: `docs/RESEARCH_PLAN.md` fixes eight weeks as the minimum for a real
     * series, and says a shorter one is reported as "not enough data" rather
     * than dropped quietly (Step 95).
     */
    val meetsResearchMinimum: Boolean get() = dayCount >= RESEARCH_MINIMUM_DAYS

    /** The quantities alone, in order — what a forecasting method is fitted on. */
    fun history(): DemandHistory = DemandHistory(days.map { it.quantitySold })

    companion object {
        /** Eight weeks, fixed in `docs/RESEARCH_PLAN.md` before any experiment ran. */
        const val RESEARCH_MINIMUM_DAYS = 56
    }
}

/**
 * One sale line, already dated in the shop's own time zone.
 *
 * The date is worked out before it gets here, on purpose: which calendar day a
 * 23:40 sale belongs to is a question about the shop's time zone, and
 * answering it inside a pure function would mean reading a zone that a test
 * cannot control (the reason D041 gives for passing `today` in).
 *
 * [quantity] is signed exactly as the stored sale line is: negative on a
 * reversal (rule 3 above).
 */
data class SoldQuantity(
    val productId: EntityId,
    val day: LocalDate,
    val quantity: Quantity,
)

/** The stretch of days a product's demand is observed over, both ends included. */
data class DemandWindow(
    val firstDay: LocalDate,
    val lastDay: LocalDate,
) {
    init {
        require(!lastDay.isBefore(firstDay)) {
            "A demand window cannot end ($lastDay) before it starts ($firstDay)."
        }
    }

    val dayCount: Int get() = (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt()
}

/**
 * Daily demand for one product over [window]: every day in the window appears
 * exactly once, quiet days as zero.
 *
 * Lines outside the window are ignored rather than pulled into the nearest day
 * — a sale from before the window is not evidence about the window's first
 * day.
 *
 * A day that nets below zero is reported as zero. With whole-sale reversals
 * dated back to their original sale (rule 3) that cannot arise from anything
 * the app does; it can arise when a reversal reached this phone by sync before
 * the sale it undoes, so the original's date is not here yet. Zero is the
 * honest floor — a shop cannot sell less than nothing — and the alternative,
 * refusing to build the series at all, would withhold a forecast because of a
 * sync ordering that fixes itself.
 */
fun dailyDemand(
    productId: EntityId,
    sold: List<SoldQuantity>,
    window: DemandWindow,
): DemandSeries {
    val netPerDay =
        sold
            .filter { it.productId == productId && it.day >= window.firstDay && it.day <= window.lastDay }
            .groupBy { it.day }
            .mapValues { (_, lines) -> lines.map { it.quantity }.sum() }

    val days =
        (0 until window.dayCount).map { offset ->
            val date = window.firstDay.plusDays(offset.toLong())
            val net = netPerDay[date]?.scaledUnits ?: 0L
            DailyDemand(date, Quantity(maxOf(net, 0L)))
        }
    return DemandSeries(productId, days)
}

/**
 * Daily demand for every product that appears in [sold], each over its own
 * window, in a fixed order (by product id) so two runs produce the same list.
 *
 * [discontinued] names the products that are no longer sold, which shortens
 * their window to their last sale (rule 4).
 */
fun dailyDemandByProduct(
    sold: List<SoldQuantity>,
    today: LocalDate,
    discontinued: Set<EntityId> = emptySet(),
    notBefore: LocalDate? = null,
): List<DemandSeries> =
    sold
        .map { it.productId }
        .distinct()
        .sortedBy { it.value }
        .mapNotNull { productId ->
            val window =
                observedWindow(
                    sold = sold,
                    productId = productId,
                    today = today,
                    discontinued = productId in discontinued,
                    notBefore = notBefore,
                ) ?: return@mapNotNull null
            dailyDemand(productId, sold, window)
        }

/**
 * The window to observe one product over, or null when there is nothing to
 * observe because the product has never been sold.
 *
 * The start is the product's first sale, or [notBefore] if that is later.
 * Product rows carry no creation date (`ProductEntity`), so days before the
 * first sale cannot be told apart from days before the product existed, and
 * counting them as zero demand would invent quiet days that may never have
 * been offered. This slightly overstates the demand rate of a product whose
 * first weeks were genuinely quiet, which is the honest trade and is recorded
 * in D049.
 *
 * The end is [today] for a product still being sold, and the product's **last
 * sale** for one that is [discontinued] — rule 4 above.
 */
fun observedWindow(
    sold: List<SoldQuantity>,
    productId: EntityId,
    today: LocalDate,
    discontinued: Boolean = false,
    notBefore: LocalDate? = null,
): DemandWindow? {
    val own = sold.filter { it.productId == productId }
    val firstSale = own.minOfOrNull { it.day } ?: return null
    val lastSale = own.maxOfOrNull { it.day } ?: return null

    val start = if (notBefore != null && notBefore > firstSale) notBefore else firstSale
    val end = if (discontinued) lastSale else today
    if (end < start) return null
    return DemandWindow(start, end)
}

/**
 * Marks the days on which the stock ledger read zero or less, by replaying
 * [movements] in the order they happened.
 *
 * A day is marked when the running balance is zero or less at any point during
 * it, including a day with no movements at all that opens at or below zero — a
 * shop that was already empty on Tuesday morning was still empty all Tuesday.
 * The one exception is the series' own first day, which is never marked on its
 * opening balance alone: see the comment on that line.
 *
 * What the mark means, exactly: *the ledger said zero or less*. That is not
 * the same as "the shelf was empty", because a sale is never blocked by stock
 * and stock may legitimately go negative when goods were sold before they were
 * ever recorded (D031). It is the only signal stored data offers,
 * `docs/RESEARCH_PLAN.md` asks for it to be calculated rather than typed, so
 * it is calculated — and reported for what it is.
 *
 * [movements] may hold other products' rows; only this series' product is
 * read. [zone] must be the same zone the series' days were built in, or a
 * movement lands on the wrong day.
 */
fun markStockOutDays(
    series: DemandSeries,
    movements: List<StockMovement>,
    zone: ZoneId,
): DemandSeries {
    val firstDay = series.firstDay ?: return series

    val deltasByDay =
        movements
            .filter { it.productId == series.productId }
            .sortedBy { it.time.occurredAt }
            // `atZone(...).toLocalDate()`, not `LocalDate.ofInstant`: the
            // latter is API 34, and minSdk is 26 (D028).
            .groupBy {
                it.time.occurredAt
                    .atZone(zone)
                    .toLocalDate()
            }.mapValues { (_, rows) -> rows.map { it.quantityDelta.scaledUnits } }

    var balance =
        deltasByDay
            .filterKeys { it < firstDay }
            .values
            .flatten()
            .sum()

    val marked =
        series.days.mapIndexed { index, day ->
            // The opening check is skipped on the series' very first day. A
            // series starts at the product's first sale, so the product was
            // plainly in stock that morning; a ledger reading zero before its
            // first recorded movement is a missing record, not an empty shelf.
            var emptyAtSomePoint = index > 0 && balance <= 0L
            deltasByDay[day.date].orEmpty().forEach { delta ->
                balance += delta
                if (balance <= 0L) emptyAtSomePoint = true
            }
            day.copy(stockOut = emptyAtSomePoint)
        }
    return series.copy(days = marked)
}
