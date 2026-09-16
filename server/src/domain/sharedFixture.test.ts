/**
 * Step 37: the same fixture, run against both implementations.
 *
 * Android is Kotlin and this is TypeScript, so the code cannot be shared.
 * The numbers are: this file and
 * `android/app/src/test/java/com/hisab/app/domain/SharedFixtureTest.kt`
 * both read `fixtures/m2_sale_stock.tsv` and must agree with it.
 *
 * Reading the real file, rather than copying its cases in here, is the whole
 * point. A case added to the fixture runs on both sides immediately, and
 * neither side can drift by quietly keeping its own numbers.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { generateId } from './id.js'
import { money, ZERO_MONEY } from './money.js'
import { quantity } from './quantity.js'
import {
  calculateCurrentStock,
  correctStock,
  damage,
  restock,
  returnStock,
  sell,
  type StockMovement,
  type StockMovementType,
} from './stock.js'
import {
  calculateLineTotal,
  calculateSaleTotal,
  completeCashSale,
  completeCreditSale,
  reverseSale,
  type SaleItem,
  type SaleLine,
} from './sale.js'

const FIXTURE_PATH = 'fixtures/m2_sale_stock.tsv'

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
  readonly expected: number[]
}

function loadCases(): FixtureCase[] {
  return readFileSync(findFixture(), 'utf8')
    .split('\n')
    .map((line) => line.replace('\r', '').trim())
    .filter((line) => line !== '' && !line.startsWith('#'))
    .map((line) => {
      const fields = line.split('\t')
      assert.ok(fields.length >= 4, `Fixture line needs at least 4 tab-separated fields: ${line}`)
      return {
        kind: fields[0]!,
        label: fields[1]!,
        input: fields[2]!,
        expected: fields.slice(3).map(Number),
      }
    })
}

/** "3000:5000;2000:2500" -> pairs of numbers. "-" means no entries at all. */
function parsePairs(input: string): Array<[number, number]> {
  if (input === '-') return []
  return input.split(';').map((entry) => {
    const [left, right] = entry.split(':')
    return [Number(left), Number(right)] as [number, number]
  })
}

const AT = new Date('2026-09-15T10:00:00.000Z')
const SHOP = 'shop-1'

function linesFrom(input: string): SaleLine[] {
  // A different product per line, so the "one line per product" rule holds.
  return parsePairs(input).map(([qty, price]) => ({
    productId: generateId(),
    quantity: quantity(qty),
    unitPrice: money(price),
  }))
}

function itemsFrom(input: string): SaleItem[] {
  const saleId = generateId()
  return parsePairs(input).map(([qty, price]) => ({
    saleId,
    productId: generateId(),
    quantity: quantity(qty),
    unitPrice: money(price),
  }))
}

/**
 * "restock:10000;sale:-3000" -> real movements built by the real functions,
 * not hand-made records. The fixture names the type and the signed delta it
 * expects; each function is asked for the movement that produces it, so the
 * signs themselves are under test too.
 */
function movementsFrom(input: string, productId = generateId()): StockMovement[] {
  if (input === '-') return []

  return input.split(';').map((entry) => {
    const [name, amount] = entry.split(':')
    const type = name as StockMovementType
    const delta = Number(amount)

    switch (type) {
      case 'restock':
        return restock(productId, quantity(delta), AT)
      case 'sale':
        return sell(productId, quantity(-delta), generateId(), AT)
      case 'return':
        return returnStock(productId, quantity(delta), AT)
      case 'damage':
        return damage(productId, quantity(-delta), AT)
      case 'correction':
        // A correction is a shelf count, so it is expressed as counted vs.
        // recorded — the pair that produces this delta.
        return correctStock(
          productId,
          quantity(Math.max(delta, 0)),
          quantity(Math.max(-delta, 0)),
          AT,
        )
      default:
        throw new Error(`Unknown movement type in fixture: ${name}`)
    }
  })
}

const cases = loadCases()

test('the shared fixture file was actually found and has cases', () => {
  assert.ok(cases.length > 0, 'no fixture cases loaded')
})

for (const fixture of cases) {
  test(`${fixture.kind}: ${fixture.label}`, () => {
    switch (fixture.kind) {
      case 'line_total': {
        const [[qty, price]] = parsePairs(fixture.input) as [[number, number]]
        assert.equal(calculateLineTotal(quantity(qty), money(price)), fixture.expected[0])
        break
      }

      case 'sale_total': {
        assert.equal(calculateSaleTotal(itemsFrom(fixture.input)), fixture.expected[0])
        break
      }

      case 'stock_balance': {
        const productId = generateId()
        assert.equal(
          calculateCurrentStock(movementsFrom(fixture.input, productId), productId),
          fixture.expected[0],
        )
        break
      }

      case 'correction': {
        const [[counted, recorded]] = parsePairs(fixture.input) as [[number, number]]
        const movement = correctStock(generateId(), quantity(counted), quantity(recorded), AT)
        assert.equal(movement.quantityDelta, fixture.expected[0])
        break
      }

      case 'credit_sale': {
        const customerId = generateId()
        const sale = completeCreditSale(SHOP, customerId, linesFrom(fixture.input), AT)
        assert.equal(sale.sale.total, fixture.expected[0], 'sale total')
        assert.equal(sale.bakiEntry?.amountDelta, fixture.expected[1], 'baki delta')
        break
      }

      case 'reversal': {
        const customerId = generateId()
        const original = completeCreditSale(SHOP, customerId, linesFrom(fixture.input), AT)
        const undone = reverseSale(original, AT)

        assert.equal(undone.sale.total, fixture.expected[0], 'reversal sale total')
        assert.equal(
          undone.stockMovements.reduce((sum, m) => sum + m.quantityDelta, 0),
          fixture.expected[1],
          'stock returned',
        )
        assert.equal(undone.bakiEntry?.amountDelta, fixture.expected[2], 'baki delta')

        // The point of the reversal: after it, nothing is left over.
        assert.equal(original.sale.total + undone.sale.total, ZERO_MONEY, 'money nets to zero')
        assert.equal(
          [...original.stockMovements, ...undone.stockMovements].reduce(
            (sum, m) => sum + m.quantityDelta,
            0,
          ),
          0,
          'stock nets to zero',
        )
        assert.equal(
          (original.bakiEntry?.amountDelta ?? 0) + (undone.bakiEntry?.amountDelta ?? 0),
          0,
          'baki nets to zero',
        )
        break
      }

      default:
        throw new Error(`Unknown fixture kind: ${fixture.kind}`)
    }
  })
}

test('a cash sale from the fixture totals the same as the credit one', () => {
  const credit = cases.find((c) => c.kind === 'credit_sale')!
  const cash = completeCashSale(SHOP, linesFrom(credit.input), AT)
  assert.equal(cash.sale.total, credit.expected[0])
  assert.equal(cash.bakiEntry, null)
})
