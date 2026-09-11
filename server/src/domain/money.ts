/**
 * Money as an integer in the smallest currency unit (poisha for BDT).
 * Never a float — see PRD.md section 24, DECISIONS.md D019.
 *
 * `Money` is branded so a plain `number` can't be passed where money is
 * expected without going through `money()`, which rejects non-integers.
 */
export type Money = number & { readonly __brand: 'Money' }

export function money(minorUnits: number): Money {
  if (!Number.isInteger(minorUnits)) {
    throw new TypeError(
      `Money must be an integer number of minor units, got ${minorUnits}. ` +
        'Money is never a float (D019).',
    )
  }
  return minorUnits as Money
}

export const ZERO_MONEY: Money = money(0)

export function addMoney(a: Money, b: Money): Money {
  return money(a + b)
}

export function subtractMoney(a: Money, b: Money): Money {
  return money(a - b)
}

/** Sums a list of Money values. Empty list sums to zero. */
export function sumMoney(values: readonly Money[]): Money {
  return values.reduce(addMoney, ZERO_MONEY)
}
