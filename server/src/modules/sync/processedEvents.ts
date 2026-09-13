// The processed-event log: what makes push idempotent (D004, D016).
// In-memory for now — same reasoning as auth (D027): Postgres arrives with
// real entities in M1, not before. Applying an event only records that its
// eventId has been seen; M1 wires in the actual Product/Customer/Sale
// effects once those entities exist.

const processedEventIds = new Set<string>()

export interface ApplyResult {
  readonly eventId: string
  readonly applied: boolean
}

/**
 * Applies an event exactly once. A repeat of the same eventId is a no-op
 * that reports it was already applied — never an error, never re-applied.
 */
export function applyEvent(eventId: string): ApplyResult {
  if (processedEventIds.has(eventId)) {
    return { eventId, applied: false }
  }
  processedEventIds.add(eventId)
  return { eventId, applied: true }
}

export function isProcessed(eventId: string): boolean {
  return processedEventIds.has(eventId)
}
