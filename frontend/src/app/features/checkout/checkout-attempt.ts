import type { CheckoutQuoteRequest } from '../../core/api/generated/models';

const STORAGE_KEY = 'bb.checkout.attempt.v1';

/** `fingerprint` identifies the request without storing it (it holds the delivery address). */
interface Attempt {
  readonly key: string;
  readonly fingerprint: string;
}

/** Fallback when sessionStorage is unavailable: still stable for retries within this page. */
let inMemory: Attempt | null = null;

/**
 * A 53-bit string hash (cyrb53). Only compared with the one stored attempt, so a collision would
 * need two different requests in the same tab to hash alike; the server still checks the body.
 */
function fingerprint(text: string): string {
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < text.length; i++) {
    const ch = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36) + ':' + text.length;
}

function read(): Attempt | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    const attempt = (raw ? JSON.parse(raw) : null) as Attempt | null;
    if (attempt && typeof attempt.key === 'string' && typeof attempt.fingerprint === 'string') {
      return attempt;
    }
  } catch {
    // Unavailable or unreadable: fall back to memory.
  }
  return inMemory;
}

/**
 * The idempotency key for placing exactly this order (checkout must be idempotent).
 *
 * The same request always gets the same key until the order is placed, including after a lost
 * response or a page reload in this tab (sessionStorage), so retrying returns the original order
 * instead of placing a second one. A changed request gets a new key, since the server rejects a
 * key reused with a different body (IDEMPOTENCY_KEY_REUSED).
 */
export function idempotencyKeyFor(
  request: CheckoutQuoteRequest & { paymentMethod: string },
): string {
  const print = fingerprint(JSON.stringify(request));
  const existing = read();
  if (existing && existing.fingerprint === print) {
    return existing.key;
  }
  const attempt: Attempt = { key: crypto.randomUUID(), fingerprint: print };
  inMemory = attempt;
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt));
  } catch {
    // Memory only.
  }
  return attempt.key;
}

/** The order was placed: the next checkout starts a new attempt. */
export function forgetAttempt(): void {
  inMemory = null;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing stored.
  }
}
