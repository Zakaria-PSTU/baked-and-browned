import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { OrderDetail } from '../../core/api/generated/models';
import { AuthSession } from '../../core/auth/auth-session';
import { CartStore } from '../cart/cart-store';
import { OrderPage } from '../orders/order-page';
import { forgetAttempt } from './checkout-attempt';
import { CheckoutPage } from './checkout-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

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
      await fixture.whenStable();
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
