import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { AdminOrderDetail, AdminPayment, Order } from '../../core/api/generated/models';
import { AdminOrderPage } from './admin-order-page';
import { AdminOrdersPage } from './admin-orders-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const summary = (n: number, overrides: Partial<Order> = {}): Order => ({
  orderId: `o${n}`,
  orderNumber: `BB-20261118-00000${n}`,
  status: 'CONFIRMED',
  paymentStatus: 'PENDING',
  paymentMethod: 'COD',
  currency: 'BDT',
  subtotal: 4430,
  deliveryFee: 60,
  discount: 0,
  total: 4490,
  createdAt: '2026-11-18T08:05:00Z',
  ...overrides,
});

const cod = (overrides: Partial<AdminPayment> = {}): AdminPayment => ({
  id: 'pay1',
  orderId: 'o1',
  orderNumber: 'BB-20261118-000001',
  method: 'COD',
  amount: 4490,
  currency: 'BDT',
  status: 'PENDING',
  createdAt: '2026-11-18T08:05:00Z',
  ...overrides,
});

const detail = (overrides: Partial<AdminOrderDetail> = {}): AdminOrderDetail => ({
  ...summary(1),
  customerUserId: 'u1',
  customerName: 'Nusrat Jahan',
  customerPhone: '+8801712345678',
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
          textValue: '<script>alert(1)</script>',
          priceDelta: 0,
        },
      ],
    },
  ],
  delivery: {
    name: 'Rafi',
    phone: '+8801812345678',
    zoneCode: 'DHANMONDI',
    zoneName: 'Dhanmondi',
    address: 'House 12, Road 5',
    notes: 'Ring twice',
    slotStartAt: '2026-11-20T10:00:00Z',
    slotEndAt: '2026-11-20T12:00:00Z',
  },
  payment: { method: 'COD', status: 'PENDING' },
  statusHistory: [],
  statusEvents: [
    { fromStatus: null, toStatus: 'CONFIRMED', actorType: 'CUSTOMER', at: '2026-11-18T08:05:00Z' },
  ],
  payments: [cod()],
  refunds: [],
  updatedAt: '2026-11-18T08:05:00Z',
  cancellationDeadline: '2026-11-19T10:00:00Z',
  cancellable: true,
  ...overrides,
});

describe('admin orders', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('AdminOrdersPage', () => {
    it('lists orders for the status in the URL and changes the filter through the URL', async () => {
      const fixture = TestBed.createComponent(AdminOrdersPage);
      fixture.componentRef.setInput('status', 'PREPARING');
      await settle();
      const req = http.expectOne((r) => r.url === '/api/v1/admin/orders');
      expect(req.request.params.get('status')).toBe('PREPARING');
      req.flush([summary(2, { status: 'PREPARING' }), summary(1, { status: 'PREPARING' })]);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;

      const rows = [...root.querySelectorAll('[data-testid="order-row"]')];
      expect(rows.map((r) => r.querySelector('a')?.getAttribute('href'))).toEqual([
        '/admin/orders/o2',
        '/admin/orders/o1',
      ]);
      expect(rows[0].textContent).toContain('Preparing');
      expect(rows[0].textContent).toContain('COD · PENDING');
      expect(rows[0].textContent).toContain('৳4,490.00');

      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      const select = root.querySelector<HTMLSelectElement>('#status-filter');
      if (!select) {
        throw new Error('no filter');
      }
      expect(select.value).toBe('PREPARING');
      select.value = '';
      select.dispatchEvent(new Event('change'));
      expect(navigate).toHaveBeenCalledWith(['/admin/orders'], {
        queryParams: { status: undefined },
      });
    });

    it('treats an unknown status as all orders', async () => {
      const fixture = TestBed.createComponent(AdminOrdersPage);
      fixture.componentRef.setInput('status', 'NOPE');
      await settle();
      const req = http.expectOne((r) => r.url === '/api/v1/admin/orders');
      expect(req.request.params.has('status')).toBe(false);
      req.flush([]);
      await fixture.whenStable();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('No orders here.');
    });
  });

  describe('AdminOrderPage', () => {
    let fixture: ComponentFixture<AdminOrderPage>;
    let root: HTMLElement;

    const open = async (order: AdminOrderDetail) => {
      fixture = TestBed.createComponent(AdminOrderPage);
      fixture.componentRef.setInput('orderId', 'o1');
      await settle();
      http.expectOne('/api/v1/admin/orders/o1').flush(order);
      await fixture.whenStable();
      root = fixture.nativeElement as HTMLElement;
    };
    const button = (label: string) =>
      [...root.querySelectorAll('button')].find((b) => b.textContent?.includes(label));
    const click = async (label: string) => {
      const found = button(label);
      if (!found) {
        throw new Error(`no button "${label}"`);
      }
      found.click();
      await fixture.whenStable();
    };
    const type = async (selector: string, value: string) => {
      const el = root.querySelector<HTMLInputElement>(selector);
      if (!el) {
        throw new Error(`missing ${selector}`);
      }
      el.value = value;
      el.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };
    const reloadWith = async (order: AdminOrderDetail) => {
      await settle();
      http.expectOne('/api/v1/admin/orders/o1').flush(order);
      await fixture.whenStable();
    };

    it('shows the whole order, with customer text as text', async () => {
      await open(detail());
      const text = root.textContent ?? '';
      expect(text).toContain('Nusrat Jahan · +8801712345678');
      expect(text).toContain('Fri, 20 Nov, 4:00 pm – 6:00 pm');
      expect(text).toContain('Notes: Ring twice');
      expect(text).toContain('Message on the cake: <script>alert(1)</script>');
      expect(root.querySelector('script')).toBeNull();
      expect(text).toContain('COD · PENDING · ৳4,490.00');
      expect(root.querySelector('[data-testid="event"]')?.textContent).toContain('Confirmed');
    });

    it('moves a confirmed order to the next step and reads it again', async () => {
      await open(detail());
      await click('Start baking');
      const change = http.expectOne('/api/v1/admin/orders/o1/status');
      expect(change.request.method).toBe('PATCH');
      expect(change.request.body).toEqual({ status: 'PREPARING' });
      change.flush(summary(1, { status: 'PREPARING' }));
      await reloadWith(
        detail({
          status: 'PREPARING',
          statusEvents: [
            ...detail().statusEvents,
            {
              fromStatus: 'CONFIRMED',
              toStatus: 'PREPARING',
              actorType: 'ADMIN',
              at: '2026-11-18T09:00:00Z',
            },
          ],
        }),
      );
      expect(root.querySelector('[data-testid="status"]')?.textContent).toContain('Preparing');
      expect(root.querySelector('[role="status"]')?.textContent).toContain('Now preparing.');
      expect(button('Mark ready')).toBeTruthy();
      expect(root.textContent).toContain('Ingredients already used are not returned to stock.');
      expect(root.querySelectorAll('[data-testid="event"]')[1].textContent).toContain(
        'Confirmed → Preparing',
      );
    });

    it('cancels only with a reason and a second click', async () => {
      await open(detail());
      await click('Cancel order');
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('reason');
      http.expectNone('/api/v1/admin/orders/o1/status');

      await type('#cancel-reason', 'Customer called: wrong date');
      await click('Cancel order');
      http.expectNone('/api/v1/admin/orders/o1/status');
      await click('Keep it');
      await click('Cancel order');
      await click('Yes, cancel order BB-20261118-000001');
      const change = http.expectOne('/api/v1/admin/orders/o1/status');
      expect(change.request.body).toEqual({
        status: 'CANCELLED',
        reason: 'Customer called: wrong date',
      });
      change.flush(summary(1, { status: 'CANCELLED' }));
      await reloadWith(detail({ status: 'CANCELLED', payments: [cod({ status: 'CANCELLED' })] }));
      expect(root.textContent).toContain('Nothing more to do for this order.');
      expect(button('Cancel order')).toBeUndefined();
      expect(button('Record cash collected')).toBeUndefined();
    });

    it('records cash collected for a delivered COD order', async () => {
      await open(detail({ status: 'DELIVERED' }));
      expect(button('Cancel order')).toBeUndefined();
      expect(root.textContent).toContain('৳4,490.00 not yet collected');
      await click('Record cash collected');
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('who collected');

      await type('#cash-note', ' Rider Karim, 20 Nov ');
      await click('Record cash collected');
      const verify = http.expectOne('/api/v1/admin/payments/pay1/verification');
      expect(verify.request.body).toEqual({ decision: 'APPROVE', note: 'Rider Karim, 20 Nov' });
      verify.flush(cod({ status: 'SUCCEEDED' }));
      await reloadWith(
        detail({
          status: 'DELIVERED',
          payments: [cod({ status: 'SUCCEEDED', verificationNote: 'Rider Karim, 20 Nov' })],
        }),
      );
      expect(button('Record cash collected')).toBeUndefined();
      expect(root.textContent).toContain('Note: Rider Karim, 20 Nov');
    });

    it('points an unpaid transfer order to payment verification', async () => {
      await open(
        detail({
          status: 'PENDING_PAYMENT',
          paymentMethod: 'MANUAL',
          payments: [cod({ method: 'MANUAL' })],
        }),
      );
      expect(root.querySelector('a[href="/admin/payments"]')).not.toBeNull();
      expect(button('Start baking')).toBeUndefined();
      expect(button('Record cash collected')).toBeUndefined();
      expect(button('Cancel order')).toBeTruthy();
    });

    it('shows a refusal and the order as it is now', async () => {
      await open(detail());
      await click('Start baking');
      http.expectOne('/api/v1/admin/orders/o1/status').flush(
        {
          title: 'Conflict',
          status: 409,
          code: 'INVALID_TRANSITION',
          detail: 'The order is no longer CONFIRMED.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await reloadWith(detail({ status: 'CANCELLED' }));
      expect(root.querySelector('[role="alert"]')?.textContent).toContain('no longer CONFIRMED');
      expect(root.querySelector('[data-testid="status"]')?.textContent).toContain('Cancelled');
    });
  });
});
