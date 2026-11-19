import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { OrderDetail } from '../../core/api/generated/models';
import { OrderPage } from './order-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const detail = (overrides: Partial<OrderDetail> = {}): OrderDetail => ({
  orderId: 'o1',
  orderNumber: 'BB-20261118-000001',
  status: 'CONFIRMED',
  paymentStatus: 'PENDING',
  paymentMethod: 'COD',
  currency: 'BDT',
  subtotal: 1250,
  deliveryFee: 60,
  discount: 0,
  total: 1310,
  createdAt: '2026-11-18T08:05:00Z',
  updatedAt: '2026-11-18T08:05:00Z',
  items: [],
  delivery: {
    name: 'Rahim',
    phone: '+8801711000000',
    address: 'House 1, Road 2',
    zoneCode: 'DHN',
    zoneName: 'Dhanmondi',
    slotStartAt: '2026-11-20T10:00:00Z',
    slotEndAt: '2026-11-20T12:00:00Z',
  },
  payment: { method: 'COD', status: 'PENDING' },
  statusHistory: [],
  cancellationDeadline: '2026-11-19T10:00:00Z',
  cancellable: true,
  ...overrides,
});

describe('OrderPage cancellation', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (order: OrderDetail) => {
    const fixture = TestBed.createComponent(OrderPage);
    fixture.componentRef.setInput('orderId', order.orderId);
    await settle();
    http.expectOne(`/api/v1/orders/${order.orderId}`).flush(order);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const click = async (testId: string) => {
      root.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!.click();
      await fixture.whenStable();
    };
    return { fixture, root, click };
  };

  it('shows the deadline in Dhaka time and cancels with an optional reason after confirming', async () => {
    const { fixture, root, click } = await open(detail());
    expect(root.querySelector('[data-testid="cancel-deadline"]')?.textContent).toContain(
      'Thu, 19 Nov, 4:00 pm',
    );
    expect(root.querySelector('[data-testid="confirm-cancel"]')).toBeNull();

    await click('start-cancel');
    const reason = root.querySelector<HTMLTextAreaElement>('#cancel-reason')!;
    reason.value = '  Plans changed  ';
    reason.dispatchEvent(new Event('input'));
    await click('confirm-cancel');

    const req = http.expectOne('/api/v1/orders/o1/cancel');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Plans changed' });
    req.flush(detail({ status: 'CANCELLED', cancellable: false }));
    await fixture.whenStable();

    expect(root.querySelector('[data-testid="order-status"]')?.textContent).toContain('Cancelled');
    expect(root.querySelector('[data-testid="cancelled"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="cancel-panel"]')).toBeNull();
  });

  it('sends no body without a reason and keeps the order when asked', async () => {
    const { root, click } = await open(detail());
    await click('start-cancel');
    await click('keep-order');
    expect(root.querySelector('[data-testid="confirm-cancel"]')).toBeNull();

    await click('start-cancel');
    await click('confirm-cancel');
    const req = http.expectOne('/api/v1/orders/o1/cancel');
    expect(req.request.body).toBeNull();
    req.flush(detail({ status: 'CANCELLED', cancellable: false }));
  });

  it('shows the server message when the window has closed', async () => {
    const { fixture, root, click } = await open(detail());
    await click('start-cancel');
    await click('confirm-cancel');
    http.expectOne('/api/v1/orders/o1/cancel').flush(
      {
        title: 'Conflict',
        status: 409,
        code: 'CANCELLATION_WINDOW_CLOSED',
        detail: 'The cancellation window for this order has closed. Please contact us for help.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(root.querySelector('[data-testid="cancel-error"]')?.textContent).toContain(
      'cancellation window for this order has closed',
    );
  });

  it('hides cancelling when the server says it is not allowed', async () => {
    const { root } = await open(detail({ cancellable: false }));
    expect(root.querySelector('[data-testid="cancel-panel"]')).toBeNull();
  });

  it('warns that a paid order is refunded after review', async () => {
    const { root } = await open(detail({ payment: { method: 'MANUAL', status: 'SUCCEEDED' } }));
    expect(root.querySelector('[data-testid="cancel-deadline"]')?.textContent).toContain(
      'review a refund',
    );
  });

  it('shows the open refund on a cancelled paid order', async () => {
    const { root } = await open(
      detail({
        status: 'CANCELLED',
        cancellable: false,
        payment: { method: 'MANUAL', status: 'SUCCEEDED', refundStatus: 'REQUESTED' },
      }),
    );
    expect(root.querySelector('[data-testid="refund-note"]')?.textContent).toContain(
      'processing a refund',
    );
  });
});
