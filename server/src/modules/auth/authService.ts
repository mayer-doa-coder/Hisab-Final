import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto'

// Steps 11-12: minimal login + token verification. Users/shops are held in
// memory and seeded below rather than in PostgreSQL — see DECISIONS.md D027
// for why (Postgres arrives with the real endpoints in M1, not before).

export interface Shop {
  readonly id: string
  readonly name: string
}

export interface User {
  readonly id: string
  readonly shopId: string
  readonly role: 'owner' | 'staff'
  readonly email: string
  readonly passwordHash: string
}

function hashPassword(password: string): string {
  const salt = randomBytes(16)
  const hash = scryptSync(password, salt, 64)
  return `${salt.toString('hex')}:${hash.toString('hex')}`
}

function verifyPassword(password: string, storedHash: string): boolean {
  const [saltHex, hashHex] = storedHash.split(':')
  if (!saltHex || !hashHex) return false

  const salt = Buffer.from(saltHex, 'hex')
  const expected = Buffer.from(hashHex, 'hex')
  const actual = scryptSync(password, salt, expected.length)

  // Constant-time compare so a wrong-length guess can't be timed apart from a
  // wrong-content one.
  return expected.length === actual.length && timingSafeEqual(expected, actual)
}

const shops: readonly Shop[] = [
  { id: 'shop-1', name: 'Rahim Store' },
  { id: 'shop-2', name: 'Karim General Store' },
]

// Demo accounts only, for exercising Steps 11-12. Real sign-up doesn't exist
// yet — nothing in this scope asks for it.
const users: readonly User[] = [
  {
    id: 'user-1',
    shopId: 'shop-1',
    role: 'owner',
    email: 'rahim@example.com',
    passwordHash: hashPassword('correct-horse-1'),
  },
  {
    id: 'user-2',
    shopId: 'shop-2',
    role: 'owner',
    email: 'karim@example.com',
    passwordHash: hashPassword('correct-horse-2'),
  },
]

export interface AuthSession {
  readonly userId: string
  readonly shopId: string
  readonly role: User['role']
}

const sessions = new Map<string, AuthSession>()

/** Returns a session token for valid credentials, or null for invalid ones. */
export function login(email: string, password: string): string | null {
  const user = users.find((candidate) => candidate.email === email)
  if (!user || !verifyPassword(password, user.passwordHash)) {
    return null
  }

  const token = randomUUID()
  sessions.set(token, { userId: user.id, shopId: user.shopId, role: user.role })
  return token
}

/** The only place shop_id is ever derived from — never a client-supplied field (D015). */
export function verifyToken(token: string): AuthSession | null {
  return sessions.get(token) ?? null
}

export function findShop(shopId: string): Shop | null {
  return shops.find((shop) => shop.id === shopId) ?? null
}
