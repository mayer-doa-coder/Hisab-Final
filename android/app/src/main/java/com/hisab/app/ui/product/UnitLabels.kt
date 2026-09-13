package com.hisab.app.ui.product

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hisab.app.R
import com.hisab.app.domain.ProductUnits

/**
 * The stored unit is a language-neutral code (D011); this is the only place
 * that turns it into words on screen, in whichever language is showing.
 */
@StringRes
fun unitLabelRes(code: String): Int =
    when (code) {
        ProductUnits.PIECE -> R.string.unit_piece
        ProductUnits.KG -> R.string.unit_kg
        ProductUnits.GRAM -> R.string.unit_gram
        ProductUnits.LITRE -> R.string.unit_litre
        ProductUnits.PACKET -> R.string.unit_packet
        ProductUnits.BOTTLE -> R.string.unit_bottle
        ProductUnits.DOZEN -> R.string.unit_dozen
        else -> R.string.unit_piece
    }

@Composable
fun unitLabel(code: String): String = stringResource(unitLabelRes(code))
