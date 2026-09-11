/**
 * Quantity as an integer scaled by 1000 (3 decimal places), so 0.5 kg = 500,
 * 3 pieces = 3000. Never a float — see PRD.md section 24, DECISIONS.md D019.
 *
 * Chosen over a plain integer because shops sell fractional quantities
 * (0.5 kg, 1.25 kg, 2.5 litre), not just whole pieces.
 */
export type Quantity = number & { readonly __brand: 'Quantity' }

const SCALE = 1000

export function quantity(scaledUnits: number): Quantity {
  if (!Number.isInteger(scaledUnits)) {
    throw new TypeError(
      `Quantity must be an integer (already scaled by ${SCALE}), got ${scaledUnits}. ` +
        'Quantity is never a float (D019).',
    )
  }
  return scaledUnits as Quantity
}

/** Converts a human-entered decimal amount (e.g. 0.5) into a Quantity. */
export function quantityFromDecimal(decimalAmount: number): Quantity {
  const scaled = Math.round(decimalAmount * SCALE)
  return quantity(scaled)
}

/** Converts a Quantity back to the decimal amount a person would read (e.g. 0.5). */
export function quantityToDecimal(qty: Quantity): number {
  return qty / SCALE
}

export const ZERO_QUANTITY: Quantity = quantity(0)

export function addQuantity(a: Quantity, b: Quantity): Quantity {
  return quantity(a + b)
}

export function subtractQuantity(a: Quantity, b: Quantity): Quantity {
  return quantity(a - b)
}

/** Sums a list of Quantity values (e.g. StockMovement.quantity_delta). Empty list sums to zero. */
export function sumQuantity(values: readonly Quantity[]): Quantity {
  return values.reduce(addQuantity, ZERO_QUANTITY)
}
