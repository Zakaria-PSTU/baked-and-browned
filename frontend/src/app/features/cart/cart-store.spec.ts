import { TestBed } from '@angular/core/testing';
import { forgetAttempt, idempotencyKeyFor } from '../checkout/checkout-attempt';
import { dhakaDate, formatDhakaRange, nextDhakaDates } from '../../shared/dhaka-time';
import { type CartItem, CartStore, MAX_LINES, MAX_QUANTITY } from './cart-store';

const item = (overrides: Partial<CartItem['line']> = {}): Omit<CartItem, 'id'> => ({
  slug: 'celebration-cake',
  productName: 'Celebration Cake',
  choices: ['Size: 2 kg'],
  line: {
    productId: 'cake',
    quantity: 1,
    selectedOptionIds: ['two'],
    customTexts: {},
    ...overrides,
  },
});

describe('CartStore', () => {
  beforeEach(() => localStorage.clear());

  const store = () => TestBed.inject(CartStore);

  it('adds lines, merges identical ones and caps quantities at the server limit', () => {
    const cart = store();
    cart.add(item({ quantity: 2 }));
    // Same choices in another order: the same line.
    cart.add(item({ selectedOptionIds: ['two'], quantity: 3 }));
    cart.add(item({ customTexts: { message: 'Hi' } }));
    expect(cart.items().length).toBe(2);
    expect(cart.items()[0].line.quantity).toBe(5);
    expect(cart.count()).toBe(6);

    cart.add(item({ quantity: 49 }));
    expect(cart.items()[0].line.quantity).toBe(MAX_QUANTITY);
    cart.setQuantity(cart.items()[0].id, 0);
    expect(cart.items()[0].line.quantity).toBe(1);
    cart.setQuantity(cart.items()[0].id, Number.NaN);
    expect(cart.items()[0].line.quantity).toBe(1);

    cart.remove(cart.items()[1].id);
    expect(cart.items().map((i) => i.line.customTexts)).toEqual([{}]);
  });

  it('survives a reload and drops anything unreadable from storage', () => {
    store().add(item());
    const saved = JSON.parse(localStorage.getItem('bb.cart.v1') ?? '[]') as unknown[];
    localStorage.setItem(
      'bb.cart.v1',
      JSON.stringify([...saved, { id: 'x', line: { quantity: 999 } }, 'junk']),
    );
    TestBed.resetTestingModule();
    expect(
      store()
        .items()
        .map((i) => i.productName),
    ).toEqual(['Celebration Cake']);

    localStorage.setItem('bb.cart.v1', '{not json');
    TestBed.resetTestingModule();
    expect(store().items()).toEqual([]);
  });

  it('holds at most one order worth of lines', () => {
    const cart = store();
    for (let i = 0; i < MAX_LINES + 1; i++) {
      cart.add(item({ productId: `p${i}` }));
    }
    expect(cart.items().length).toBe(MAX_LINES);
    expect(cart.full()).toBe(true);
    cart.clear();
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('bb.cart.v1')).toBeNull();
  });
});

describe('checkout attempt', () => {
  beforeEach(() => {
    sessionStorage.clear();
    forgetAttempt();
  });

  const request = (address: string) => ({
    deliverySlotId: 's1',
    delivery: { name: 'Nusrat', phone: '+8801712345678', area: 'DHANMONDI', address },
    items: [{ productId: 'p', quantity: 1, selectedOptionIds: [], customTexts: {} }],
    paymentMethod: 'COD',
  });

  it('keeps one key per request until the order is placed', () => {
    const first = idempotencyKeyFor(request('House 12, Road 5'));
    expect(idempotencyKeyFor(request('House 12, Road 5'))).toBe(first);
    const changed = idempotencyKeyFor(request('House 14, Road 5'));
    expect(changed).not.toBe(first);
    forgetAttempt();
    expect(idempotencyKeyFor(request('House 14, Road 5'))).not.toBe(changed);
  });

  it('never stores the delivery address', () => {
    idempotencyKeyFor(request('House 12, Road 5'));
    expect(sessionStorage.getItem('bb.checkout.attempt.v1')).not.toContain('House');
  });
});

describe('Dhaka time', () => {
  it('uses the Dhaka calendar date, which is ahead of UTC', () => {
    // 19:30 UTC is 01:30 the next day in Dhaka (UTC+6).
    expect(dhakaDate(new Date('2026-11-18T19:30:00Z'))).toBe('2026-11-19');
    expect(nextDhakaDates(new Date('2026-11-18T19:30:00Z'), 3)).toEqual([
      '2026-11-19',
      '2026-11-20',
      '2026-11-21',
    ]);
  });

  it('shows slot times in Dhaka', () => {
    expect(formatDhakaRange('2026-11-20T10:00:00Z', '2026-11-20T12:00:00Z')).toBe(
      'Fri, 20 Nov, 4:00 pm – 6:00 pm',
    );
  });
});
