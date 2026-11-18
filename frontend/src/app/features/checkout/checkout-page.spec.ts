import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { ManualPaymentAccount, OrderDetail } from '../../core/api/generated/models';
import { AuthSession } from '../../core/auth/auth-session';
import { CartStore } from '../cart/cart-store';
import { OrderPage } from '../orders/order-page';
import { forgetAttempt } from './checkout-attempt';
import { CheckoutPage } from './checkout-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const BKASH: ManualPaymentAccount = {
  provider: 'BKASH',
  label: 'bKash (Merchant)',
  accountName: 'Baked & Browned',
  accountNumber: '01700000000',
  note: 'Use Payment, not Send Money.',
};

const order = (overrides: Partial<OrderDetail> = {}): OrderDetail => ({
  orderId: 'o1',
  orderNumber: 'BB-20261120-000001',
  status: 'CONFIRMED',
  paymentStatus: 'PENDING',
  paymentMethod: 'COD',
  currency: 'BDT',
  subtotal: 4430,
  deliveryFee: 60,
  discount: 0,
  total: 4490,
  items: [
    {
      productId: 'cake',
      productName: 'Celebration Cake',
      quantity: 1,
      unitBasePrice: 2200,
      unitOptionsPrice: 2230,
      unitPrice: 4430,
      lineTotal: 4430,
      customizations: [
        {
          groupId: 'size',
          groupName: 'Size',
          optionId: 'two',
          optionName: '2 kg',
          priceDelta: 1800,
        },
        {
          groupId: 'message',
          groupName: 'Message on the cake',
          optionId: null,
          optionName: null,
          textValue: '<img src=x onerror=alert(1)>',
          priceDelta: 0,
        },
      ],
    },
  ],
  delivery: {
    name: 'Nusrat Jahan',
    phone: '+8801712345678',
    zoneCode: 'DHANMONDI',
    zoneName: 'Dhanmondi',
    address: 'House 12, Road 5',
    slotStartAt: '2026-11-20T10:00:00Z',
    slotEndAt: '2026-11-20T12:00:00Z',
  },
  payment: { method: 'COD', status: 'PENDING' },
  statusHistory: [],
  updatedAt: '2026-11-18T10:00:00Z',
  cancellationDeadline: '2026-11-19T10:00:00Z',
  cancellable: true,
  ...overrides,
});

describe('checkout', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    forgetAttempt();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('CheckoutPage', () => {
    let fixture: ComponentFixture<CheckoutPage>;
    let root: HTMLElement;
    let cart: CartStore;

    const field = <T extends HTMLElement>(selector: string): T => {
      const el = root.querySelector<T>(selector);
      if (!el) {
        throw new Error(`missing ${selector}`);
      }
      return el;
    };
    const type = async (selector: string, value: string, event = 'input') => {
      const el = field<HTMLInputElement>(selector);
      el.value = value;
      el.dispatchEvent(new Event(event));
      await fixture.whenStable();
    };
    const button = (label: string) => {
      const found = [...root.querySelectorAll('button')].find((b) =>
        b.textContent?.includes(label),
      );
      if (!found) {
        throw new Error(`no button "${label}"`);
      }
      return found;
    };

    beforeEach(async () => {
      TestBed.inject(AuthSession).start('token', 900, {
        id: 'u1',
        fullName: 'Nusrat Jahan',
        phone: '+8801712345678',
        role: 'CUSTOMER',
      });
      cart = TestBed.inject(CartStore);
      cart.add({
        slug: 'celebration-cake',
        productName: 'Celebration Cake',
        choices: ['Size: 2 kg', 'Message on the cake: <b>Hi</b>'],
        line: {
          productId: 'cake',
          quantity: 1,
          selectedOptionIds: ['two'],
          customTexts: { message: '<b>Hi</b>' },
        },
      });
      fixture = TestBed.createComponent(CheckoutPage);
      root = fixture.nativeElement as HTMLElement;
      await settle();
      http
        .expectOne('/api/v1/delivery-zones')
        .flush([{ code: 'DHANMONDI', name: 'Dhanmondi', deliveryFee: 60 }]);
      const slots = http.expectOne((r) => r.url === '/api/v1/delivery-slots');
      expect(slots.request.params.get('date')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      slots.flush([
        {
          id: 's1',
          startAt: '2026-11-20T10:00:00Z',
          endAt: '2026-11-20T12:00:00Z',
          remainingUnits: 4,
        },
      ]);
      http.expectOne('/api/v1/payments/manual-accounts').flush([BKASH]);
      await fixture.whenStable();
    });

    it('places a transfer order when the business takes transfers', async () => {
      await type('#area', 'DHANMONDI', 'change');
      await type('#address', 'House 12, Road 5');
      field<HTMLInputElement>('input[name="slot"]').click();
      field<HTMLInputElement>('input[value="MANUAL"]').click();
      await fixture.whenStable();
      expect(field('[data-testid="manual-hint"]').textContent).toContain('15 minutes');

      button('Review total').click();
      http
        .expectOne('/api/v1/checkout/quote')
        .flush({ subtotal: 4430, deliveryFee: 60, discount: 0, total: 4490, currency: 'BDT' });
      await fixture.whenStable();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      button('then transfer ৳4,490.00').click();
      const placed = http.expectOne('/api/v1/orders');
      expect(placed.request.body).toMatchObject({ paymentMethod: 'MANUAL' });
      placed.flush(order({ status: 'PENDING_PAYMENT', paymentMethod: 'MANUAL' }), {
        status: 201,
        statusText: 'Created',
      });
      await fixture.whenStable();
      expect(navigate).toHaveBeenCalledWith(['/orders', 'o1'], { queryParams: { placed: 1 } });
    });

    it('quotes, then places a cash-on-delivery order idempotently', async () => {
      // Prefilled from the account; markup in a cart label is only text.
      expect(field<HTMLInputElement>('#name').value).toBe('Nusrat Jahan');
      expect(root.querySelector('aside b')).toBeNull();
      expect(root.textContent).toContain('Message on the cake: <b>Hi</b>');

      await type('#phone', '01812 345678');
      await type('#area', 'DHANMONDI', 'change');
      await type('#address', '  House 12, Road 5  ');
      field<HTMLInputElement>('input[name="slot"]').click();
      await fixture.whenStable();
      expect(root.textContent).toContain('Fri, 20 Nov, 4:00 pm – 6:00 pm');

      button('Review total').click();
      const quote = http.expectOne('/api/v1/checkout/quote');
      expect(quote.request.method).toBe('POST');
      expect(quote.request.body).toEqual({
        deliverySlotId: 's1',
        delivery: {
          name: 'Nusrat Jahan',
          phone: '+8801812345678',
          area: 'DHANMONDI',
          address: 'House 12, Road 5',
        },
        items: [
          {
            productId: 'cake',
            quantity: 1,
            selectedOptionIds: ['two'],
            customTexts: { message: '<b>Hi</b>' },
          },
        ],
      });
      quote.flush({ subtotal: 4430, deliveryFee: 60, discount: 0, total: 4490, currency: 'BDT' });
      await fixture.whenStable();
      expect(field('[data-testid="quote"]').textContent).toContain('৳4,490.00');

      // The response is lost: the retry must use the same key, so it cannot place a second order.
      button('Place order').click();
      const first = http.expectOne('/api/v1/orders');
      expect(first.request.body).toMatchObject({ paymentMethod: 'COD', deliverySlotId: 's1' });
      const key = (first.request.body as { idempotencyKey: string }).idempotencyKey;
      first.error(new ProgressEvent('error'), { status: 0 });
      await fixture.whenStable();
      expect(field('[role="alert"]').textContent).toContain('Cannot reach Baked & Browned');
      expect(root.querySelector('[data-testid="quote"]')).toBeNull();

      button('Review total').click();
      http
        .expectOne('/api/v1/checkout/quote')
        .flush({ subtotal: 4430, deliveryFee: 60, discount: 0, total: 4490, currency: 'BDT' });
      await fixture.whenStable();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      button('Place order').click();
      const retry = http.expectOne('/api/v1/orders');
      expect((retry.request.body as { idempotencyKey: string }).idempotencyKey).toBe(key);
      retry.flush(order(), { status: 201, statusText: 'Created' });
      await fixture.whenStable();

      expect(navigate).toHaveBeenCalledWith(['/orders', 'o1'], { queryParams: { placed: 1 } });
      expect(cart.items()).toEqual([]);
    });

    it('drops the quote on any change and asks for what is missing', async () => {
      button('Review total').click();
      await fixture.whenStable();
      expect(field('[role="alert"]').textContent).toContain('check the delivery details');
      expect(root.textContent).toContain('Choose the area we deliver to.');

      await type('#area', 'DHANMONDI', 'change');
      await type('#address', 'House 12, Road 5');
      button('Review total').click();
      await fixture.whenStable();
      expect(field('[role="alert"]').textContent).toContain('choose a delivery time');

      field<HTMLInputElement>('input[name="slot"]').click();
      button('Review total').click();
      http
        .expectOne('/api/v1/checkout/quote')
        .flush({ subtotal: 4430, deliveryFee: 60, discount: 0, total: 4490, currency: 'BDT' });
      await fixture.whenStable();
      expect(root.querySelector('[data-testid="quote"]')).not.toBeNull();

      await type('#notes', 'Ring twice');
      expect(root.querySelector('[data-testid="quote"]')).toBeNull();
      expect(button('Review total')).toBeTruthy();
    });

    it('shows the server reason when the quote is refused', async () => {
      await type('#area', 'DHANMONDI', 'change');
      await type('#address', 'House 12, Road 5');
      field<HTMLInputElement>('input[name="slot"]').click();
      button('Review total').click();
      http.expectOne('/api/v1/checkout/quote').flush(
        {
          title: 'Unprocessable',
          status: 422,
          code: 'LEAD_TIME_NOT_MET',
          detail: '"Fondant" needs at least 72 hours\' notice.',
        },
        { status: 422, statusText: 'Unprocessable Content' },
      );
      await fixture.whenStable();
      expect(field('[role="alert"]').textContent).toContain('"Fondant" needs at least 72 hours');
    });
  });

  it('offers only cash on delivery when no transfer account is configured', async () => {
    TestBed.inject(CartStore).add({
      slug: 'cookies',
      productName: 'Cookies',
      choices: [],
      line: { productId: 'c', quantity: 1, selectedOptionIds: [], customTexts: {} },
    });
    const fixture = TestBed.createComponent(CheckoutPage);
    await settle();
    http.expectOne('/api/v1/delivery-zones').flush([]);
    http.expectOne((r) => r.url === '/api/v1/delivery-slots').flush([]);
    http.expectOne('/api/v1/payments/manual-accounts').flush([]);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('input[value="COD"]')).not.toBeNull();
    expect(root.querySelector('input[value="MANUAL"]')).toBeNull();
  });

  describe('manual payment', () => {
    const pending = (overrides: Partial<OrderDetail> = {}) =>
      order({
        status: 'PENDING_PAYMENT',
        paymentMethod: 'MANUAL',
        payment: { method: 'MANUAL', status: 'PENDING' },
        expiresAt: new Date(Date.now() + 14 * 60_000 + 5_000).toISOString(),
        ...overrides,
      });

    /**
     * Opens the order page. When the payment panel shows, it loads the accounts, and the page is
     * not stable until that request is answered, so `accounts` answers it here.
     */
    const open = async (body: OrderDetail, accounts?: ManualPaymentAccount[]) => {
      const fixture = TestBed.createComponent(OrderPage);
      fixture.componentRef.setInput('orderId', 'o1');
      await settle();
      http.expectOne('/api/v1/orders/o1').flush(body);
      if (accounts) {
        // The panel renders, then its resource sends the request a tick later.
        let requests = http.match('/api/v1/payments/manual-accounts');
        for (let tick = 0; tick < 10 && requests.length === 0; tick++) {
          fixture.detectChanges();
          await settle();
          requests = http.match('/api/v1/payments/manual-accounts');
        }
        expect(requests.length).toBe(1);
        requests[0].flush(accounts);
      }
      await fixture.whenStable();
      return fixture;
    };

    it('shows where to pay and the time left, then records the transaction ID', async () => {
      const fixture = await open(pending(), [BKASH]);
      const root = fixture.nativeElement as HTMLElement;

      expect(root.querySelector('[data-testid="order-status"]')?.textContent).toContain(
        'Waiting for payment',
      );
      const account = root.querySelector('[data-testid="account"]')?.textContent ?? '';
      expect(account).toContain('bKash (Merchant)');
      expect(account).toContain('01700000000');
      expect(account).toContain('Use Payment, not Send Money.');
      expect(root.textContent).toContain('Send exactly ৳4,490.00');
      expect(root.querySelector('[data-testid="time-left"]')?.textContent).toMatch(/14:0[45]/);
      // One provider configured: no choice to make.
      expect(root.querySelector('#provider')).toBeNull();

      const submit = () =>
        [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('I have paid'));
      submit()?.click();
      await fixture.whenStable();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('transaction ID');

      const reference = root.querySelector<HTMLInputElement>('#reference');
      const proof = root.querySelector<HTMLInputElement>('#proof');
      if (!reference || !proof) {
        throw new Error('no payment form');
      }
      // A file of the wrong type is refused before anything is sent.
      const exe = new File(['MZ'], 'pay.exe', { type: 'application/x-msdownload' });
      Object.defineProperty(proof, 'files', { value: [exe], configurable: true });
      proof.dispatchEvent(new Event('change'));
      await fixture.whenStable();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('JPEG, PNG or PDF');

      const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'receipt.png', {
        type: 'image/png',
      });
      Object.defineProperty(proof, 'files', { value: [png], configurable: true });
      proof.dispatchEvent(new Event('change'));
      reference.value = '  8AB3KQ9Z21 ';
      reference.dispatchEvent(new Event('input'));
      submit()?.click();

      const sent = http.expectOne('/api/v1/payments/o1/manual-submission');
      const form = sent.request.body as FormData;
      expect(form.get('provider')).toBe('BKASH');
      expect(form.get('transactionReference')).toBe('8AB3KQ9Z21');
      expect((form.get('proof') as File).name).toBe('receipt.png');
      sent.flush(
        {
          id: 'm1',
          paymentId: 'pay1',
          provider: 'BKASH',
          transactionReference: '8AB3KQ9Z21',
          status: 'SUBMITTED',
          hasProof: true,
          createdAt: '2026-11-18T10:05:00Z',
        },
        { status: 201, statusText: 'Created' },
      );
      await settle();

      // The order is read again and now waits for the bakery's check.
      http.expectOne('/api/v1/orders/o1').flush(
        pending({
          payment: { method: 'MANUAL', status: 'PENDING', manualSubmissionStatus: 'SUBMITTED' },
        }),
      );
      await fixture.whenStable();
      expect(root.querySelector('[data-testid="manual-payment"]')).toBeNull();
      expect(root.querySelector('[data-testid="manual-submitted"]')).not.toBeNull();
      expect(root.querySelector('[data-testid="order-status"]')?.textContent).toContain(
        'Checking your payment',
      );
    });

    it('shows a used reference refused by the server', async () => {
      const fixture = await open(pending({ expiresAt: '2000-01-01T00:00:00Z' }), [
        BKASH,
        { ...BKASH, provider: 'NAGAD', label: 'Nagad', note: null },
      ]);
      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('[data-testid="time-up"]')).not.toBeNull();

      const provider = root.querySelector<HTMLSelectElement>('#provider');
      const reference = root.querySelector<HTMLInputElement>('#reference');
      if (!provider || !reference) {
        throw new Error('no payment form');
      }
      [...root.querySelectorAll('button')]
        .find((b) => b.textContent?.includes('I have paid'))
        ?.click();
      await fixture.whenStable();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('Choose how you paid.');

      provider.value = 'NAGAD';
      provider.dispatchEvent(new Event('change'));
      reference.value = 'NG-1234';
      reference.dispatchEvent(new Event('input'));
      [...root.querySelectorAll('button')]
        .find((b) => b.textContent?.includes('I have paid'))
        ?.click();
      const sent = http.expectOne('/api/v1/payments/o1/manual-submission');
      expect((sent.request.body as FormData).get('provider')).toBe('NAGAD');
      expect((sent.request.body as FormData).has('proof')).toBe(false);
      sent.flush(
        {
          title: 'Conflict',
          status: 409,
          code: 'TRANSACTION_REFERENCE_USED',
          detail: 'This transaction reference has already been used.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await fixture.whenStable();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('already been used');
    });

    it('shows nothing to pay for a transfer order that is no longer pending', async () => {
      const fixture = await open(
        pending({ status: 'CONFIRMED', payment: { method: 'MANUAL', status: 'SUCCEEDED' } }),
      );
      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('[data-testid="manual-payment"]')).toBeNull();
      expect(root.querySelector('[data-testid="order-status"]')?.textContent).toContain(
        'Confirmed',
      );
    });
  });

  describe('OrderPage', () => {
    it('shows the placed order with messages as text', async () => {
      const fixture = TestBed.createComponent(OrderPage);
      fixture.componentRef.setInput('orderId', 'o1');
      fixture.componentRef.setInput('placed', '1');
      await settle();
      http.expectOne('/api/v1/orders/o1').flush(order());
      await fixture.whenStable();

      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('[data-testid="placed"]')).not.toBeNull();
      expect(root.textContent).toContain('Order BB-20261120-000001');
      expect(root.textContent).toContain('Confirmed');
      expect(root.textContent).toContain('Size: 2 kg');
      expect(root.textContent).toContain('(+ ৳1,800.00)');
      expect(root.textContent).toContain('Message on the cake: <img src=x onerror=alert(1)>');
      expect(root.querySelector('img')).toBeNull();
      expect(root.textContent).toContain('Pay ৳4,490.00 in cash when it arrives.');
    });

    it('explains an order that is not yours or does not exist', async () => {
      const fixture = TestBed.createComponent(OrderPage);
      fixture.componentRef.setInput('orderId', 'nope');
      await settle();
      http
        .expectOne('/api/v1/orders/nope')
        .flush({ title: 'Not Found', status: 404 }, { status: 404, statusText: 'Not Found' });
      await fixture.whenStable();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'We could not find this order',
      );
    });
  });
});
