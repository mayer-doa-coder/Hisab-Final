package com.hisab.app.domain

/**
 * An explicit currency rather than an assumption baked into the code, even
 * though V1 only supports BDT — DECISIONS.md D019, PRD.md section 24
 * (Shop.currency).
 */
enum class Currency {
    BDT,
}

val DEFAULT_CURRENCY = Currency.BDT
