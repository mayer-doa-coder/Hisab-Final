package com.hisab.app.data

/**
 * Every product belongs to a shop (`docs/DATA_MODEL.md`), but the phone has no
 * login screen yet — the shop a user belongs to only exists on the server so
 * far (Steps 11–12). Until the app signs in and learns its real shop id
 * (Steps 30–33), local rows are written under this fixed id.
 *
 * This is a local placeholder only. It is never sent to the server as the
 * owner of anything: the backend always derives shop_id from the session
 * token and ignores whatever the client says (D015).
 */
const val LOCAL_SHOP_ID = "local-shop"
