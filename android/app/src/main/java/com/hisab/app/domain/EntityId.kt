package com.hisab.app.domain

import java.util.UUID

/**
 * Every entity that can be created offline (Product, Customer, Sale,
 * StockMovement, BakiEntry — not just SyncOutbox events) gets a globally
 * unique ID generated on the device at creation time. That ID is the
 * entity's permanent primary key, locally and on the server — there is no
 * separate local-ID-to-server-ID mapping step.
 * See DECISIONS.md D018.
 *
 * Uses `java.util.UUID`, built into the platform — no dependency needed
 * (D006: keep dependencies minimal).
 */
@JvmInline
value class EntityId(
    val value: String,
)

fun generateId(): EntityId = EntityId(UUID.randomUUID().toString())
