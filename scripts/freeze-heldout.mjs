#!/usr/bin/env node
// Step 77 — freezing the held-out test set, and checking it again at Step 108.
//
//   node scripts/freeze-heldout.mjs hash   <file>   # freeze: write the hash
//   node scripts/freeze-heldout.mjs verify <file>   # Step 108: check it
//   node scripts/freeze-heldout.mjs check-overlap <heldout> [dev]
//
// Only the hash is ever committed. The sentences stay with a writer or a
// labeler — see research/language/heldout/README.md for why.

import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const HASH_FILE = join(HERE, '..', 'research', 'language', 'heldout', 'heldout-v1.sha256')
const DEV_SET = join(HERE, '..', 'research', 'language', 'dev', 'dev-set.jsonl')

const [command, target, devOverride] = process.argv.slice(2)

/** Hashed as raw bytes, so a changed line ending counts as a change. */
function hashOf(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

/** The plan's "simple cleanup" for comparing two sentences: lowercase, collapse spaces, Bangla digits to 0-9. */
function cleaned(text) {
  return text
    .toLowerCase()
    .replace(/[০-৯]/g, (d) => String(d.codePointAt(0) - 0x09e6))
    .replace(/\s+/g, ' ')
    .trim()
}

function readSentences(path) {
  return readFileSync(path, 'utf8')
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line, at) => {
      try {
        return JSON.parse(line)
      } catch {
        throw new Error(`${path} line ${at + 1} is not valid JSON`)
      }
    })
}

function requireTarget() {
  if (!target || !existsSync(target)) {
    console.error(`need a path to the held-out file. "${target ?? ''}" is not there.`)
    process.exit(1)
  }
}

switch (command) {
  case 'hash': {
    requireTarget()
    if (existsSync(HASH_FILE)) {
      console.error(
        `${HASH_FILE} already exists. A frozen set is frozen — re-freezing would\n` +
          'quietly replace the thing the Step 108 result is supposed to be checked\n' +
          'against. Delete it deliberately, and record why in DECISIONS.md, if the\n' +
          'set genuinely has to be re-cut.',
      )
      process.exit(1)
    }
    const digest = hashOf(target)
    writeFileSync(HASH_FILE, `${digest}\n`)
    console.log(`sha256  ${digest}`)
    console.log(`written to ${HASH_FILE}`)
    console.log('\nCommit that hash file. Do NOT commit the sentences themselves.')
    break
  }

  case 'verify': {
    requireTarget()
    if (!existsSync(HASH_FILE)) {
      console.error(`no frozen hash at ${HASH_FILE} — nothing to check against.`)
      process.exit(1)
    }
    const expected = readFileSync(HASH_FILE, 'utf8').trim()
    const actual = hashOf(target)
    if (expected === actual) {
      console.log(`OK — matches the hash frozen at Step 77 (${actual})`)
      break
    }
    console.error('MISMATCH — this is not the file that was frozen.')
    console.error(`  frozen at Step 77: ${expected}`)
    console.error(`  this file:         ${actual}`)
    console.error('\nNo result computed from this file counts. Find the frozen copy.')
    process.exit(1)
  }

  case 'check-overlap': {
    requireTarget()
    const dev = devOverride ?? DEV_SET
    const devTexts = new Set(readSentences(dev).map((row) => cleaned(row.text)))
    const rows = readSentences(target)
    const overlapping = rows.filter((row) => devTexts.has(cleaned(row.text)))

    console.log(`held-out sentences: ${rows.length}`)
    console.log(`development sentences: ${devTexts.size}`)
    if (overlapping.length === 0) {
      console.log('no overlap — the two sets are genuinely separate')
      break
    }
    console.log(`\n${overlapping.length} sentence(s) appear in both sets and must be removed first:`)
    for (const row of overlapping) console.log(`  ${row.id}  ${row.text}`)
    process.exit(1)
  }

  default:
    console.log('commands: hash <file> | verify <file> | check-overlap <heldout> [dev]')
}
