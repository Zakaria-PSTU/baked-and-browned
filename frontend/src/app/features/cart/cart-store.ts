import { Injectable, computed, signal } from '@angular/core';
import type { CartLine } from '../../core/api/generated/models';

/** The server's limits (CheckoutDtos): lines per order and quantity per line. */
export const MAX_LINES = 50;
export const MAX_QUANTITY = 50;

/**
 * One line in the cart: what the server needs (`line`) plus labels to show it with. No prices:
 * the server prices every line at checkout, so nothing here can go stale or be trusted.
 */
export interface CartItem {
  /** Local id for editing this line. */
  readonly id: string;
  readonly slug: string;
  readonly productName: string;
  /** "Size: 2 kg", "Message on the cake: Happy 30th", in display order. */
  readonly choices: readonly string[];
  readonly line: CartLine;
}

const STORAGE_KEY = 'bb.cart.v1';

function isCartItem(value: unknown): value is CartItem {
  const item = value as CartItem;
  return (
    typeof item === 'object' &&
    item !== null &&
    typeof item.id === 'string' &&
    typeof item.slug === 'string' &&
    typeof item.productName === 'string' &&
    Array.isArray(item.choices) &&
    item.choices.every((c) => typeof c === 'string') &&
    typeof item.line === 'object' &&
    item.line !== null &&
    typeof item.line.productId === 'string' &&
    Number.isInteger(item.line.quantity) &&
    item.line.quantity >= 1 &&
    item.line.quantity <= MAX_QUANTITY &&
    Array.isArray(item.line.selectedOptionIds) &&
    item.line.selectedOptionIds.every((o) => typeof o === 'string') &&
    typeof item.line.customTexts === 'object' &&
    item.line.customTexts !== null &&
    Object.values(item.line.customTexts).every((t) => typeof t === 'string')
  );
}

/** Same product with the same choices: adding it again raises the quantity instead. */
function sameChoices(a: CartLine, b: CartLine): boolean {
  const texts = (l: CartLine) => JSON.stringify(Object.entries(l.customTexts).sort());
  return (
    a.productId === b.productId &&
    [...a.selectedOptionIds].sort().join() === [...b.selectedOptionIds].sort().join() &&
    texts(a) === texts(b)
  );
}

/**
 * The customer's cart, kept in this browser only (localStorage) so it survives a reload or a
 * detour through login. Storage can be missing or full (private windows); the cart then simply
 * lasts for the visit. Anything unreadable in storage is dropped, never trusted.
 */
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly state = signal<readonly CartItem[]>(this.load());

  readonly items = this.state.asReadonly();

  /** Total pieces, for the header badge. */
  readonly count = computed(() => this.state().reduce((n, i) => n + i.line.quantity, 0));

  /** Whether another distinct line fits in one order. */
  readonly full = computed(() => this.state().length >= MAX_LINES);

  constructor() {
    // Another tab changed the cart: follow it.
    if (typeof window !== 'undefined') {
      window.addEventListener('storage', (event) => {
        if (event.key === STORAGE_KEY) {
          this.state.set(this.load());
        }
      });
    }
  }

  /** Adds a line, or raises the quantity of an identical one (capped at the server's limit). */
  add(item: Omit<CartItem, 'id'>): void {
    const items = [...this.state()];
    const existing = items.findIndex((i) => sameChoices(i.line, item.line));
    if (existing >= 0) {
      const current = items[existing];
      const quantity = Math.min(MAX_QUANTITY, current.line.quantity + item.line.quantity);
      items[existing] = { ...current, line: { ...current.line, quantity } };
    } else if (items.length < MAX_LINES) {
      items.push({ ...item, id: crypto.randomUUID() });
    } else {
      return;
    }
    this.save(items);
  }

  setQuantity(id: string, quantity: number): void {
    const q = Math.min(MAX_QUANTITY, Math.max(1, Math.trunc(quantity) || 1));
    this.save(
      this.state().map((i) => (i.id === id ? { ...i, line: { ...i.line, quantity: q } } : i)),
    );
  }

  remove(id: string): void {
    this.save(this.state().filter((i) => i.id !== id));
  }

  clear(): void {
    this.save([]);
  }

  private save(items: readonly CartItem[]): void {
    this.state.set(items);
    try {
      if (items.length === 0) {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
      }
    } catch {
      // No storage: the cart lasts for this visit only.
    }
  }

  private load(): readonly CartItem[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed.filter(isCartItem).slice(0, MAX_LINES) : [];
    } catch {
      return [];
    }
  }
}
