/**
 * Every transaction records when it actually happened (occurredAt, set by
 * the device — possibly offline, possibly hours before syncing) separately
 * from when the backend actually processed it (serverReceivedAt).
 *
 * Never treat server-receipt time as transaction time — an offline sale that
 * syncs hours later must still report its real occurredAt.
 * See PRD.md section 24, DECISIONS.md D019.
 */
export interface TransactionTime {
  /** ISO 8601. Set on the device when the transaction happened. */
  readonly occurredAt: string
  /** ISO 8601, or null until the event has actually reached the backend. */
  readonly serverReceivedAt: string | null
}

export function transactionTimeAtCreation(occurredAt: Date): TransactionTime {
  return { occurredAt: occurredAt.toISOString(), serverReceivedAt: null }
}

/** Called once, by the backend, when it actually processes the sync event. */
export function markServerReceived(time: TransactionTime, receivedAt: Date): TransactionTime {
  return { occurredAt: time.occurredAt, serverReceivedAt: receivedAt.toISOString() }
}

export function isSynced(time: TransactionTime): boolean {
  return time.serverReceivedAt !== null
}
