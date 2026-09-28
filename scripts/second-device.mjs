#!/usr/bin/env node
// A second device for Steps 71 and 72, for a shop that only owns one phone.
//
// This is not a mock. It signs in as the same shop and speaks the same two
// endpoints the phone speaks (POST /sync/push, GET /sync/changes), sending
// the same envelope shape the phone's outbox sends, and keeping its own
// state in its own file: its own device id, its own cursor, its own copy of
// the products. To the server it is simply another device.
//
// What it is good for: proving two devices converge (Step 71), and driving a
// real conflicting edit against the phone (Step 72). What it is not: a
// second Android app. It has no Room database and no UI, so it cannot tell
// you anything about what a second phone would *show*. For that, see the
// manual options in CURRENT_PHASE.md.
//
// Usage (server defaults to http://127.0.0.1:3000, shop to rahim@example.com):
//   node scripts/second-device.mjs sync
//   node scripts/second-device.mjs add "Tea" 3550
//   node scripts/second-device.mjs edit <productId> "Tea" 4000
//   node scripts/second-device.mjs list
//   node scripts/second-device.mjs reset
//
// Add --state <file> to run more than one of these at once, each with its
// own identity.

import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs'

// Split `--name value` flags from plain positional words in one pass, so
// "edit <id> <name> <price> --server http://…" works whichever order it is
// written in.
const flags = {}
const words = []
const argv = process.argv.slice(2)
for (let at = 0; at < argv.length; at += 1) {
  if (argv[at].startsWith('--')) {
    flags[argv[at].slice(2)] = argv[at + 1]
    at += 1
  } else {
    words.push(argv[at])
  }
}

const BASE = flags.server ?? 'http://127.0.0.1:3000'
const EMAIL = flags.email ?? 'rahim@example.com'
const PASSWORD = flags.password ?? 'correct-horse-1'
const STATE_FILE = flags.state ?? 'second-device.state.json'

const [command, ...rest] = words

function loadState() {
  if (!existsSync(STATE_FILE)) {
    return { deviceId: randomUUID(), token: null, cursor: 0, outbox: [], products: {} }
  }
  return JSON.parse(readFileSync(STATE_FILE, 'utf8'))
}

function saveState(state) {
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2))
}

async function call(method, path, body, token) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const json = text ? JSON.parse(text) : {}
  if (!response.ok) {
    throw new Error(`${method} ${path} -> HTTP ${response.status}: ${text}`)
  }
  return json
}

async function signIn(state) {
  if (state.token) return state.token
  const { token } = await call('POST', '/auth/login', { email: EMAIL, password: PASSWORD })
  state.token = token
  return token
}

/** The same envelope shape `SyncOutboxEntity` sends from the phone (D026). */
function queue(state, entityId, operation, payload, baseRevision) {
  state.outbox.push({
    eventId: randomUUID(),
    entityType: 'Product',
    entityId,
    operation,
    payload,
    baseRevision,
    clientTimestamp: new Date().toISOString(),
  })
}

async function sync(state) {
  const token = await signIn(state)
  let pushed = 0
  let conflicts = 0
  let rejected = 0

  if (state.outbox.length > 0) {
    const { results } = await call('POST', '/sync/push', { events: state.outbox }, token)
    const stillWaiting = []
    for (const event of state.outbox) {
      const result = results.find((one) => one.eventId === event.eventId)
      if (result?.status === 'applied' || result?.status === 'already-applied') {
        pushed += 1
      } else if (result?.status === 'conflict') {
        conflicts += 1
        // Kept, exactly as the phone keeps it: the pull below brings the
        // version the server actually has, and a person decides after that.
        stillWaiting.push({ ...event, refusedWith: result.code })
      } else {
        rejected += 1
        stillWaiting.push({ ...event, refusedWith: result?.code ?? 'unknown' })
      }
    }
    state.outbox = stillWaiting
  }

  // Pull page by page, saving the cursor as we go, the way the phone does.
  let pulled = 0
  for (;;) {
    const page = await call('GET', `/sync/changes?after=${state.cursor}`, undefined, token)
    for (const change of page.events) {
      if (change.entityType === 'Product') {
        state.products[change.entityId] = change.payload
      }
      pulled += 1
    }
    const advanced = page.cursor > state.cursor
    if (advanced) state.cursor = page.cursor
    if (page.events.length === 0 || !advanced) break
  }

  saveState(state)
  return { pushed, pulled, conflicts, rejected }
}

const state = loadState()

switch (command) {
  case 'sync': {
    console.log(await sync(state))
    break
  }

  case 'add': {
    const [name, price] = rest
    const id = randomUUID()
    queue(state, id, 'create', {
      name,
      aliases: [],
      unit: 'piece',
      sellingPricePoisha: Number(price ?? 1000),
      purchasePricePoisha: null,
      active: true,
    }, null)
    saveState(state)
    console.log(`queued create ${id} "${name}" — run "sync" to send it`)
    break
  }

  case 'edit': {
    const [id, name, price] = rest
    const known = state.products[id]
    if (!known) {
      console.error(`this device has never seen product ${id}. Run "sync" first.`)
      process.exit(1)
    }
    queue(state, id, 'update', {
      ...known,
      name: name ?? known.name,
      sellingPricePoisha: Number(price ?? known.sellingPricePoisha),
      revision: known.revision + 1,
    }, known.revision)
    saveState(state)
    console.log(
      `queued update ${id} from revision ${known.revision} — run "sync" to send it.\n` +
        'Edit the same product on the phone before syncing either one, and you have Step 72.',
    )
    break
  }

  case 'list': {
    const rows = Object.entries(state.products)
      .filter(([, product]) => !product.deletedAt)
      .map(([id, product]) => ({
        id,
        name: product.name,
        price: product.sellingPricePoisha,
        revision: product.revision,
      }))
    console.log(`${rows.length} products known to this device (cursor ${state.cursor})`)
    console.table(rows.slice(0, 40))
    break
  }

  case 'reset': {
    if (existsSync(STATE_FILE)) rmSync(STATE_FILE)
    console.log(`forgot everything in ${STATE_FILE} — next run is a brand new device`)
    break
  }

  default: {
    console.log('commands: sync | add <name> <pricePoisha> | edit <id> <name> <pricePoisha> | list | reset')
    console.log('flags: --server --email --password --state')
  }
}
