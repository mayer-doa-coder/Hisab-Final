#!/usr/bin/env bash
# Shows what the Hisab app on a USB-connected phone has actually stored.
#
# For checking the screens by hand without Android Studio's Database
# Inspector: record a sale on the phone, run this, and see the rows it wrote —
# the sale, its lines, the stock movements, the customer and the baki entry.
#
# It copies the database off the phone first and reads the copy, so nothing on
# the phone is changed. Room writes through a write-ahead log, and recent rows
# live in hisab.db-wal rather than hisab.db, so all three files are copied;
# copying only hisab.db makes a full database look empty.
#
# Needs: the debug build installed (run-as only works on debuggable apps),
# USB debugging on, and Node 22.13+ (it uses Node's built-in node:sqlite, so
# nothing new is installed).
#
# Run from the repository root:  bash scripts/phone-db.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="com.hisab.app"
OUT_DIR="android/build/phone-db"

# adb: an explicit $ADB, else one on PATH, else the SDK's own copy.
if [ -z "${ADB:-}" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="adb"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    elif [ -n "${LOCALAPPDATA:-}" ] && [ -f "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
    else
        echo "phone-db: cannot find adb. Set ADB=/path/to/adb and run again." >&2
        exit 2
    fi
fi

# Git Bash rewrites anything that looks like a Unix path before adb sees it.
export MSYS_NO_PATHCONV=1

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "phone-db: no phone connected. Plug it in, unlock it, and allow USB debugging." >&2
    exit 2
fi

if ! "$ADB" shell run-as "$PACKAGE" ls databases/hisab.db >/dev/null 2>&1; then
    echo "phone-db: no Hisab database on the phone. Install the debug build and open the app once." >&2
    exit 2
fi

# Stopping the app first means nothing is half-written while the files are
# copied. Every confirmed sale is already safely on disk; reopening the app
# afterwards is all it takes.
"$ADB" shell am force-stop "$PACKAGE"
echo "(Hisab was closed on the phone so the copy is consistent — just reopen it.)"
echo

cd "$ROOT"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

for file in hisab.db hisab.db-wal hisab.db-shm; do
    # exec-out, not shell: shell would mangle the binary bytes.
    "$ADB" exec-out run-as "$PACKAGE" cat "databases/$file" >"$OUT_DIR/$file" 2>/dev/null || rm -f "$OUT_DIR/$file"
done

node --no-warnings - "$OUT_DIR/hisab.db" <<'NODE'
const { DatabaseSync } = require('node:sqlite')
const db = new DatabaseSync(process.argv[2])

const all = (sql) => db.prepare(sql).all()
const taka = (poisha) => (Number(poisha) / 100).toFixed(2)
const qty = (scaled) => String(Number(scaled) / 1000)
const when = (millis) => (millis == null ? 'not yet' : new Date(Number(millis)).toISOString())
const day = (epochDay) =>
  epochDay == null ? 'none' : new Date(Number(epochDay) * 86400000).toISOString().slice(0, 10)

console.log('Rows per table')
for (const table of ['product', 'sale', 'sale_item', 'stock_movement', 'customer', 'baki_entry', 'sync_outbox']) {
  console.log(`  ${table.padEnd(15)} ${all(`SELECT COUNT(*) AS n FROM ${table}`)[0].n}`)
}

console.log('\nCurrent stock (the sum of each product\'s movements — never a stored number)')
for (const row of all(`
  SELECT p.name, p.unit, COALESCE(SUM(m.quantityDeltaScaled), 0) AS stock
  FROM product p LEFT JOIN stock_movement m ON m.productId = p.id
  WHERE p.deletedAt IS NULL
  GROUP BY p.id ORDER BY p.name`)) {
  console.log(`  ${row.name} — ${qty(row.stock)} ${row.unit}`)
}

console.log('\nSales, oldest first')
for (const row of all(`
  SELECT s.id, s.payment, s.totalPoisha, s.occurredAt, s.serverReceivedAt, s.reversesSaleId, c.name AS customer,
         (SELECT COUNT(*) FROM sale_item i WHERE i.saleId = s.id) AS lines
  FROM sale s LEFT JOIN customer c ON c.id = s.customerId
  ORDER BY s.occurredAt`)) {
  const who = row.customer ? `, owed by ${row.customer}` : ''
  const undo = row.reversesSaleId ? `, reverses ${row.reversesSaleId}` : ''
  console.log(`  ${row.payment} ৳${taka(row.totalPoisha)} — ${row.lines} line(s)${who}${undo}`)
  console.log(`    id ${row.id}`)
  console.log(`    happened ${when(row.occurredAt)}, reached server: ${when(row.serverReceivedAt)}`)
}

console.log('\nStock movements, oldest first')
for (const row of all(`
  SELECT m.movementType, m.quantityDeltaScaled, m.sourceReference, p.name
  FROM stock_movement m LEFT JOIN product p ON p.id = m.productId
  ORDER BY m.occurredAt, m.id`)) {
  const delta = Number(row.quantityDeltaScaled)
  const why = row.sourceReference ? `  (${row.sourceReference})` : ''
  console.log(`  ${row.movementType.padEnd(10)} ${(delta > 0 ? '+' : '') + qty(delta)} ${row.name ?? '(product gone)'}${why}`)
}

console.log('\nBaki entries')
const bakiRows = all(`
  SELECT b.entryType, b.amountDeltaPoisha, b.dueDateEpochDay, b.reference, b.serverReceivedAt, c.name,
         (SELECT s.totalPoisha FROM sale s WHERE s.id = b.reference) AS saleTotal
  FROM baki_entry b LEFT JOIN customer c ON c.id = b.customerId
  ORDER BY b.occurredAt`)
if (bakiRows.length === 0) console.log('  none')
for (const row of bakiRows) {
  // A reversal's entry references the sale it undid, not itself (SaleRepository),
  // so it should be the exact opposite of that sale's total, not equal to it.
  const expected = row.entryType === 'REVERSAL' ? -Number(row.saleTotal) : Number(row.saleTotal)
  const matches =
    row.saleTotal == null
      ? 'NO MATCHING SALE'
      : expected === Number(row.amountDeltaPoisha)
        ? row.entryType === 'REVERSAL'
          ? 'exactly undoes the sale it references'
          : 'equals its sale total'
        : `DOES NOT match the sale it references (৳${taka(row.saleTotal)})`
  console.log(`  ${row.entryType} ৳${taka(row.amountDeltaPoisha)} owed by ${row.name ?? '(customer gone)'} — ${matches}`)
  console.log(`    due ${day(row.dueDateEpochDay)}, reached server: ${when(row.serverReceivedAt)}`)
}

console.log('\nWhat each customer owes (the sum of their baki entries)')
const owes = all(`
  SELECT c.name, COALESCE(SUM(b.amountDeltaPoisha), 0) AS owed
  FROM customer c LEFT JOIN baki_entry b ON b.customerId = c.id
  WHERE c.deletedAt IS NULL GROUP BY c.id ORDER BY c.name`)
if (owes.length === 0) console.log('  no customers')
for (const row of owes) console.log(`  ${row.name} — ৳${taka(row.owed)}`)

console.log('\nWaiting to sync, by kind (empty once a sync has sent everything)')
const queued = all(`SELECT entityType, status, COUNT(*) AS n FROM sync_outbox GROUP BY entityType, status`)
if (queued.length === 0) console.log('  nothing')
for (const row of queued) console.log(`  ${row.entityType} (${row.status}): ${row.n}`)
NODE
