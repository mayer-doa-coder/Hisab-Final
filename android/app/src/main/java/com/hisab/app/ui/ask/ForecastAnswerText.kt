package com.hisab.app.ui.ask

import android.content.Context
import android.content.res.Configuration
import com.hisab.app.R
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.forecast.ReorderRecommendation
import com.hisab.app.domain.forecast.StockoutOutlook
import com.hisab.app.domain.language.AnswerLanguage
import com.hisab.app.domain.language.AskAnswer
import com.hisab.app.domain.language.LowStockItem
import com.hisab.app.domain.language.ReorderItem
import com.hisab.app.domain.toDisplayString
import com.hisab.app.ui.product.unitLabelRes
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * The words of the stock-forecast answers (Steps 89–90), in Bangla or English.
 *
 * The facts live in [AskAnswer] with no language in them; this is the only place
 * they become sentences, so the Bangla and English renderings cannot come to
 * disagree about a figure. Every sentence is a string resource in both language
 * files (`ask_*`), and every number is formatted here and handed in as text, so
 * the digits follow the language — ৩ in Bangla, 3 in English — and the
 * resource's wording never chooses them.
 *
 * The language is the *question's*, not the app's setting (D048): a reply
 * belongs to the person who asked. [language] is what
 * [AnswerLanguage.forQuestion] returned.
 *
 * The five M5 answers (stock, baki, overdue, today's and a period's sales) have
 * no wording yet — that arrives with the Ask screen, which is not in the plan's
 * M5 or M6 steps — so this returns null for them rather than inventing a
 * second, unreviewed set of sentences.
 *
 * Product names are user text in any script. They are placed into the sentence
 * as they are; giving each script its own font is the screen's job
 * (`scriptAwareText`, D010).
 */
object ForecastAnswerText {
    fun render(
        context: Context,
        answer: AskAnswer,
        language: AnswerLanguage,
        today: LocalDate,
    ): String? {
        val words = Words(localized(context, language), localeOf(language), today)
        return when (answer) {
            is AskAnswer.Reorder -> words.reorder(answer.productName, answer.unit, answer.recommendation)
            is AskAnswer.ReorderList -> words.reorderList(answer)
            is AskAnswer.LowStock -> words.lowStock(answer)
            is AskAnswer.PredictedStockout -> words.stockout(answer)
            is AskAnswer.NotEnoughData -> words.notEnoughData(answer)
            else -> null
        }
    }

    private fun localeOf(language: AnswerLanguage): Locale =
        when (language) {
            // Bangladesh, so the digits are Bengali. Resource lookup falls through
            // to the default set, which is Bangla (D009), as there is no values-bn.
            AnswerLanguage.BANGLA -> Locale.forLanguageTag("bn-BD")

            AnswerLanguage.ENGLISH -> Locale.ENGLISH
        }

    /** The same app, read as if the phone were set to [language] — whatever it is really set to. */
    private fun localized(
        context: Context,
        language: AnswerLanguage,
    ): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(localeOf(language))
        return context.createConfigurationContext(configuration)
    }

    private class Words(
        private val context: Context,
        private val locale: Locale,
        private val today: LocalDate,
    ) {
        private fun text(
            id: Int,
            vararg args: Any,
        ): String = context.getString(id, *args)

        private fun amount(quantity: Quantity): String = quantity.toDisplayString(locale)

        private fun whole(number: Int): String = NumberFormat.getIntegerInstance(locale).format(number)

        private fun unit(code: String): String = context.getString(unitLabelRes(code))

        // `DecimalStyle` is what makes the day number use the language's own digits:
        // a formatter writes ASCII digits whatever its locale unless it is told not to.
        private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", locale).withDecimalStyle(DecimalStyle.of(locale))

        fun reorder(
            productName: String,
            unitCode: String,
            recommendation: ReorderRecommendation,
        ): String {
            val unit = unit(unitCode)
            val headline =
                if (recommendation.enoughStock) {
                    text(R.string.ask_reorder_none, productName)
                } else {
                    text(R.string.ask_reorder_buy, productName, amount(recommendation.quantityToBuy), unit)
                }

            // The lines are the recommendation's own fields, so the sum a reader
            // does — stock + buy = demand + margin + rounding — comes out right.
            val lines = mutableListOf(headline)
            lines += text(R.string.ask_line_current_stock, amount(recommendation.countedStock), unit)
            if (recommendation.stockWasNegative) {
                lines += text(R.string.ask_line_negative_stock, amount(recommendation.currentStock), unit)
            }
            lines +=
                text(
                    R.string.ask_line_expected_demand,
                    whole(recommendation.leadTimeDays),
                    amount(recommendation.expectedDemand),
                    unit,
                )
            lines +=
                text(
                    R.string.ask_line_safety_margin,
                    whole(recommendation.safetyPercent),
                    amount(recommendation.safetyMargin),
                    unit,
                )
            if (recommendation.roundedUpBy != Quantity.ZERO) {
                lines += text(R.string.ask_line_rounded_up, amount(recommendation.roundedUpBy), unit)
            }
            return lines.joinToString("\n")
        }

        fun reorderList(answer: AskAnswer.ReorderList): String {
            val lines = mutableListOf<String>()
            if (answer.items.isEmpty()) {
                lines += text(R.string.ask_reorder_list_none)
            } else {
                lines += text(R.string.ask_reorder_list_header)
                lines += answer.items.map(::reorderItem)
            }
            if (answer.notEnoughData > 0) lines += text(R.string.ask_not_enough_data_count, whole(answer.notEnoughData))
            return lines.joinToString("\n")
        }

        private fun reorderItem(item: ReorderItem): String =
            text(
                R.string.ask_reorder_list_item,
                item.productName,
                amount(item.recommendation.quantityToBuy),
                unit(item.unit),
            )

        fun lowStock(answer: AskAnswer.LowStock): String {
            val lines = mutableListOf<String>()
            if (answer.items.isEmpty()) {
                lines += text(R.string.ask_low_stock_none)
            } else {
                lines += text(R.string.ask_low_stock_header, whole(answer.leadTimeDays))
                lines += answer.items.map(::lowStockItem)
            }
            if (answer.notEnoughData > 0) lines += text(R.string.ask_not_enough_data_count, whole(answer.notEnoughData))
            return lines.joinToString("\n")
        }

        private fun lowStockItem(item: LowStockItem): String =
            text(
                R.string.ask_low_stock_item,
                item.productName,
                amount(item.stock),
                unit(item.unit),
                outlookPhrase(item.outlook),
            )

        private fun outlookPhrase(outlook: StockoutOutlook): String =
            when (outlook) {
                StockoutOutlook.AlreadyOut -> text(R.string.ask_low_stock_out)
                is StockoutOutlook.InDays -> text(R.string.ask_low_stock_days, whole(outlook.days))
                is StockoutOutlook.NotSoon -> text(R.string.ask_low_stock_later, whole(outlook.lookaheadDays))
            }

        fun stockout(answer: AskAnswer.PredictedStockout): String {
            val headline =
                when (val outlook = answer.outlook) {
                    StockoutOutlook.AlreadyOut -> {
                        text(R.string.ask_stockout_already, answer.productName)
                    }

                    is StockoutOutlook.InDays -> {
                        text(
                            R.string.ask_stockout_in_days,
                            answer.productName,
                            whole(outlook.days),
                            dateFormat.format(today.plusDays(outlook.days.toLong())),
                        )
                    }

                    is StockoutOutlook.NotSoon -> {
                        text(R.string.ask_stockout_not_soon, answer.productName, whole(outlook.lookaheadDays))
                    }
                }
            return headline + "\n" + text(R.string.ask_line_current_stock, amount(answer.stock), unit(answer.unit))
        }

        fun notEnoughData(answer: AskAnswer.NotEnoughData): String {
            // Never sold at all is a different sentence from "too little so far": "sold on 0 of 0
            // days" reads like a fault, and the plain truth is that there is nothing to go on yet.
            if (answer.daysObserved == 0) {
                return text(R.string.ask_not_sold_yet, answer.productName, amount(answer.stock), unit(answer.unit))
            }
            return text(
                R.string.ask_not_enough_data,
                answer.productName,
                whole(answer.daysObserved),
                whole(answer.demandDays),
                amount(answer.stock),
                unit(answer.unit),
            )
        }
    }
}
