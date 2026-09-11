/**
 * An explicit currency field rather than an assumption baked into the code,
 * even though V1 only supports BDT — DECISIONS.md D019, PRD.md section 24
 * (Shop.currency).
 */
export type Currency = 'BDT'

export const DEFAULT_CURRENCY: Currency = 'BDT'
