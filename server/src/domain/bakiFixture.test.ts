/**
 * Step 49: the same baki fixture, run against both implementations.
 *
 * Android is Kotlin and this is TypeScript, so the code cannot be shared. The
 * numbers are: this file and
 * `android/app/src/test/java/com/hisab/app/domain/BakiFixtureTest.kt` both read
 * `fixtures/m3_baki.tsv` and must agree with it. The header of that file
 * explains the token language a ledger is written in.
 *
 * Every ledger is built with the real functions (`addCredit`, `receivePayment`,
 * `reverseEntry`, `completeCreditSale`, `reverseSale`), not hand-made rows, so
 * the signs and the types are under test as well as the sums.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import {
  addCredit,
  calculateBalance,
  isOverdue,
  overdueAmount,
  receivePayment,
  reverseEntry,
} from './baki.js'
import type { BakiEntry } from './bakiEntry.js'
import { generateId, type EntityId } from './id.js'
import { money } from './money.js'
import { quantity } from './quantity.js'
import { completeCreditSale, reverseSale, type SaleTransaction } from './sale.js'

const FIXTURE_PATH = 'fixtures/m3_baki.tsv'
const START = new Date('2026-09-15T10:00:00.000Z')
const SHOP = 'shop-1'

/** Walks up from the working directory until the fixture turns up, so it does not matter where the tests run from. */
function findFixture(): string {
  let directory = resolve(process.cwd())
  for (;;) {
    const candidate = join(directory, FIXTURE_PATH)
    if (existsSync(candidate)) return candidate

    const parent = dirname(directory)
    if (parent === directory) {
      throw new Error(`Could not find ${FIXTURE_PATH} above ${process.cwd()}`)
    }
    directory = parent
  }
}

interface FixtureCase {
  readonly kind: string
  readonly label: string
  readonly input: string
  readonly expected: string
}

function loadCases(): FixtureCase[] {
  return readFileSync(findFixture(), 'utf8')
    .split('\n')
    .map((line) => line.replace('\r', '').trim())
    .filter((line) => line !== '' && !line.startsWith('#'))
    .map((line) => {
      const fields = line.split('\t')
      assert.equal(fields.length, 4, `Fixture line needs exactly 4 tab-separated fields: ${line}`)
      return { kind: fields[0]!, label: fields[1]!, input: fields[2]!, expected: fields[3]! }
    })
}

/** Builds one customer's ledger from the fixture's token language, in order. */
function ledger(input: string, customer: EntityId): BakiEntry[] {
  if (input === '-') return []

  const entries: BakiEntry[] = []
  const sales = new Map<number, SaleTransaction>()

  input.split(';').forEach((token, index) => {
    const at = new Date(START.getTime() + index * 60_000)
    const [name, argument] = token.split(':') as [string, string]
    const [amountText, dueText] = argument.split('@') as [string, string | undefined]
    const due = dueText ?? null

    switch (name) {
      case 'credit':
        entries.push(addCredit(customer, money(Number(amountText)), at, due))
        break
      case 'payment':
        entries.push(receivePayment(customer, money(Number(amountText)), at))
        break
      case 'sale': {
        const line = {
          productId: generateId(),
          quantity: quantity(1000),
          unitPrice: money(Number(amountText)),
        }
        const sale = completeCreditSale(SHOP, customer, [line], at, due)
        sales.set(index, sale)
        entries.push(sale.bakiEntry!)
        break
      }
      case 'undo':
        entries.push(reverseEntry(entries[Number(amountText)]!, at))
        break
      case 'unsale':
        entries.push(reverseSale(sales.get(Number(amountText))!, at).bakiEntry!)
        break
      default:
        throw new Error(`Unknown token in fixture: ${token}`)
    }
  })
  return entries
}

const cases = loadCases()

test('the shared baki fixture file was actually found and has cases', () => {
  assert.ok(cases.length > 0, 'no fixture cases loaded')
  for (const kind of ['baki_balance', 'baki_overdue', 'baki_refused']) {
    assert.ok(
      cases.some((c) => c.kind === kind),
      `no '${kind}' cases in ${FIXTURE_PATH}`,
    )
  }
})

for (const fixture of cases) {
  test(`${fixture.kind}: ${fixture.label}`, () => {
    const customer = generateId()

    switch (fixture.kind) {
      case 'baki_balance': {
        const entries = ledger(fixture.input, customer)
        assert.equal(calculateBalance(entries, customer), Number(fixture.expected))

        // Somebody else's baki, sitting in the same list, must change nothing.
        const stranger = generateId()
        const mixed = [
          ...entries,
          addCredit(stranger, money(99_999), START),
          receivePayment(stranger, money(1), START),
        ]
        assert.equal(calculateBalance(mixed, customer), calculateBalance(entries, customer))
        break
      }

      case 'baki_overdue': {
        const [today, tokens] = fixture.input.split('|') as [string, string]
        const entries = ledger(tokens, customer)
        const expected = Number(fixture.expected)

        assert.equal(overdueAmount(entries, customer, today), expected)
        assert.equal(isOverdue(entries, customer, today), expected > 0)
        break
      }

      case 'baki_refused': {
        assert.equal(fixture.expected, 'refused')
        assert.throws(
          () => ledger(fixture.input, customer),
          (error: unknown) => error instanceof Error && error.message.length > 0,
        )
        break
      }

      default:
        throw new Error(`Unknown fixture kind: ${fixture.kind}`)
    }
  })
}
