#!/usr/bin/env node
// Step 75 — builds the development language dataset.
//
// The Research Data Plan allows template-generated phrases in the
// *development* set and saves the generator here so the set can be rebuilt
// and audited. Templates are explicitly **not** allowed in the held-out set
// (Step 76), because they repeat the very patterns the rules are written
// against — see docs/RESEARCH_PLAN.md.
//
//   node research/language/generate-dev-set.mjs
//
// Writes research/language/dev/dev-set.jsonl and prints a summary.

import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT = join(HERE, 'dev', 'dev-set.jsonl')

// Made-up names only — the plan forbids real customer data.
const CUSTOMERS = [
  { bn: 'রহিম', rm: 'rahim' },
  { bn: 'করিম', rm: 'karim' },
  { bn: 'সালমা', rm: 'salma' },
  { bn: 'জসিম', rm: 'josim' },
  { bn: 'নাসরিন', rm: 'nasrin' },
]

const PRODUCTS = [
  { bn: 'চিনি', rm: 'chini', en: 'sugar' },
  { bn: 'চাল', rm: 'chal', en: 'rice' },
  { bn: 'তেল', rm: 'tel', en: 'oil' },
  { bn: 'কোক', rm: 'coke', en: 'coke' },
  { bn: 'ডাল', rm: 'dal', en: 'dal' },
  { bn: 'লবণ', rm: 'lobon', en: 'salt' },
]

const PERIODS = [
  { bn: 'এই সপ্তাহে', rm: 'ei soptahe', value: 'this_week' },
  { bn: 'এই মাসে', rm: 'ei mase', value: 'this_month' },
  { bn: 'গত মাসে', rm: 'goto mase', value: 'last_month' },
  { bn: 'গতকাল', rm: 'kalke', value: 'yesterday' },
]

/**
 * The fixed rule from the plan, not a judgement call: Bengali letters only
 * is `bangla`, Latin letters only is `romanized`, both is `mixed`. Digits
 * and punctuation do not count either way.
 */
function languageTypeOf(text) {
  const hasBengali = /[ঀ-৿]/.test(text.replace(/[০-৯]/g, ''))
  const hasLatin = /[A-Za-z]/.test(text)
  if (hasBengali && hasLatin) return 'mixed'
  if (hasBengali) return 'bangla'
  if (hasLatin) return 'romanized'
  return 'romanized'
}

const rows = []
const seen = new Set()
let nextId = 1

function add(text, intent, entities) {
  // The hand-written section below repeats a few sentences the templates
  // already produce. Counting the same sentence twice would quietly weight
  // it double in the Step 83 numbers, so the first one wins.
  const key = text.trim().toLowerCase()
  if (seen.has(key)) return
  seen.add(key)

  rows.push({
    id: `dev-${String(nextId++).padStart(4, '0')}`,
    text,
    language_type: languageTypeOf(text),
    intent,
    entities,
    writer: 'W0-generator',
    set: 'dev',
  })
}

const product = (surface, value) => ({ type: 'product', surface, value })
const customer = (surface, value) => ({ type: 'customer', surface, value })
const period = (surface, value) => ({ type: 'period', surface, value })

// GET_STOCK — "how much of X is left"
for (const p of PRODUCTS) {
  add(`${p.rm} stock koto`, 'GET_STOCK', [product(p.rm, p.en)])
  add(`${p.rm} koyta ache`, 'GET_STOCK', [product(p.rm, p.en)])
  add(`${p.bn} স্টক কত`, 'GET_STOCK', [product(p.bn, p.en)])
  add(`${p.bn} কয়টা আছে`, 'GET_STOCK', [product(p.bn, p.en)])
  add(`${p.bn} stock koto`, 'GET_STOCK', [product(p.bn, p.en)])
  add(`${p.rm} কত আছে`, 'GET_STOCK', [product(p.rm, p.en)])
}

// GET_CUSTOMER_BAKI — "how much does X owe"
for (const c of CUSTOMERS) {
  add(`${c.rm} er baki koto`, 'GET_CUSTOMER_BAKI', [customer(c.rm, c.rm)])
  add(`${c.rm} koto taka pabo`, 'GET_CUSTOMER_BAKI', [customer(c.rm, c.rm)])
  add(`${c.bn}ের বাকি কত`, 'GET_CUSTOMER_BAKI', [customer(c.bn, c.rm)])
  add(`${c.bn} কত টাকা পাবো`, 'GET_CUSTOMER_BAKI', [customer(c.bn, c.rm)])
  add(`${c.bn} er baki koto`, 'GET_CUSTOMER_BAKI', [customer(c.bn, c.rm)])
  add(`${c.rm} এর বাকি কত`, 'GET_CUSTOMER_BAKI', [customer(c.rm, c.rm)])
}

// GET_OVERDUE — "who is past their due date"
const overdue = [
  ['kar baki meyad periyeche', 'romanized'],
  ['কার বাকির মেয়াদ পেরিয়েছে', 'bangla'],
  ['ke ke taka dey nai', 'romanized'],
  ['কে কে টাকা দেয়নি', 'bangla'],
  ['overdue customer ke ke', 'romanized'],
  ['কোন কোন customer এর সময় শেষ', 'mixed'],
  ['meyad sesh hoyeche kar', 'romanized'],
  ['কার টাকা বাকি পড়ে আছে', 'bangla'],
  ['due date periye gese kader', 'romanized'],
  ['কাদের baki overdue', 'mixed'],
]
for (const [text] of overdue) add(text, 'GET_OVERDUE', [])

// GET_TODAY_SALES — "how much did I sell today"
const today = [
  'ajke koto sell hoise',
  'aj koto bikri holo',
  'আজকে কত বিক্রি হয়েছে',
  'আজ কত টাকার বিক্রি',
  'ajke total sell koto',
  'আজকের sell কত',
  'aj koto taka ashlo',
  'আজকে কত আয় হলো',
  'today koto bikri',
  'আজ কত বিক্রি হইছে',
]
for (const text of today) add(text, 'GET_TODAY_SALES', [])

// GET_PERIOD_SALES — "how much did I sell over some stretch of time"
for (const p of PERIODS) {
  add(`${p.rm} koto sell hoise`, 'GET_PERIOD_SALES', [period(p.rm, p.value)])
  add(`${p.bn} কত বিক্রি হয়েছে`, 'GET_PERIOD_SALES', [period(p.bn, p.value)])
  add(`${p.bn} koto bikri`, 'GET_PERIOD_SALES', [period(p.bn, p.value)])
  add(`${p.rm} এ কত টাকার বিক্রি`, 'GET_PERIOD_SALES', [period(p.rm, p.value)])
}

// A few written by hand rather than from a template, including the plan's
// own examples, so the set is not purely templated shapes.
add('rahim er baki koto', 'GET_CUSTOMER_BAKI', [customer('rahim', 'rahim')])
add('রহিমের baki কত', 'GET_CUSTOMER_BAKI', [customer('রহিম', 'rahim')])
add('coke koyta ase', 'GET_STOCK', [product('coke', 'coke')])
add('চিনি stock কত', 'GET_STOCK', [product('চিনি', 'sugar')])
add('ajke koto sell hoise', 'GET_TODAY_SALES', [])
add('vai coke ta koyta ache dekhen to', 'GET_STOCK', [product('coke', 'coke')])
add('একটু দেখেন তো চিনি কত আছে', 'GET_STOCK', [product('চিনি', 'sugar')])
add('karim er kache koto poisa baki', 'GET_CUSTOMER_BAKI', [customer('karim', 'karim')])
add('এই মাসে মোট কত বিক্রি হলো', 'GET_PERIOD_SALES', [period('এই মাসে', 'this_month')])
add('kar kar baki meyad sesh', 'GET_OVERDUE', [])

writeFileSync(OUT, rows.map((row) => JSON.stringify(row)).join('\n') + '\n')

const byIntent = {}
const byLanguage = {}
for (const row of rows) {
  byIntent[row.intent] = (byIntent[row.intent] ?? 0) + 1
  byLanguage[row.language_type] = (byLanguage[row.language_type] ?? 0) + 1
}

console.log(`wrote ${rows.length} examples to ${OUT}`)
console.log('by intent:  ', byIntent)
console.log('by language:', byLanguage)
