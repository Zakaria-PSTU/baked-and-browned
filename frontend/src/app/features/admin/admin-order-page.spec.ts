import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type {
  AdminOrderDetail,
  AdminPayment,
  OrderStatus,
  PaymentStatus,
} from '../../core/api/generated/models';
import { AdminOrderPage } from './admin-order-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const ORDER_URL = '/api/v1/admin/orders/o1';

const codPayment = (status: PaymentStatus): AdminPayment => ({
  id: 'pay1',
  orderId: 'o1',
  orderNumber: 'BB-20261120-000001',
  method: 'COD',
  amount: 1310,
  currency: 'BDT',
  status,
  createdAt: '2026-11-20T04:00:00Z',
  verificationNote: status === 'SUCCEEDED' ? 'Rider handed it in' : null,
});

const order = (
  status: OrderStatus,
  paymentStatus: PaymentStatus = 'PENDING',
): AdminOrderDetail => ({
  orderId: 'o1',
  orderNumber: 'BB-20261120-000001',
  status,
  paymentStatus,
  paymentMethod: 'COD',
  subtotal: 1250,
  deliveryFee: 60,
  discount: 0,
  total: 1310,
  currency: 'BDT',
  createdAt: '2026-11-20T04:00:00Z',
  updatedAt: '2026-11-20T04:00:00Z',
  cancellationDeadline: '2026-11-21T04:00:00Z',
  cancellable: false,
  items: [],
  delivery: {
    name: 'Test Customer',
    phone: '+8801700000000',
    zoneCode: 'DHANMONDI',
    zoneName: 'Dhanmondi',
    address: 'House 1, Road 1',
    slotStartAt: '2026-11-22T04:00:00Z',
    slotEndAt: '2026-11-22T06:00:00Z',
  },
  payment: { method: 'COD', status: paymentStatus },
  statusHistory: [],
  customerUserId: 'u1',
  customerName: 'Test Customer',
  customerPhone: '+8801700000000',
  statusEvents: [],
  payments: [codPayment(paymentStatus)],
  refunds: [],
});

describe('AdminOrderPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminOrderPage>;
  let root: HTMLElement;

  const button = (label: string) => {
    const found = [...root.querySelectorAll('button')].find((b) => b.textContent?.includes(label));
    if (!found) {
      throw new Error(`no button "${label}"`);
    }
    return found;
  };
  const banner = () => root.querySelector('[role="status"]')?.textContent?.trim() ?? null;
  const paymentLine = () => root.querySelector('[data-testid="payment"]')?.textContent ?? '';
  const stale = () => root.querySelector('[data-testid="stale"]');
  const serverError = () => ({ title: 'Server Error', status: 500 });

  async function open(initial: AdminOrderDetail): Promise<void> {
    fixture.componentRef.setInput('orderId', 'o1');
    await settle();
    http.expectOne(ORDER_URL).flush(initial);
    await fixture.whenStable();
  }

  async function recordCash(): Promise<void> {
    const note = root.querySelector<HTMLInputElement>('#cash-note');
    if (!note) {
      throw new Error('no cash note');
    }
    note.value = 'Rider handed it in';
    note.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button('Record cash collected').click();
    const verify = http.expectOne('/api/v1/admin/payments/pay1/verification');
    expect(verify.request.body).toEqual({ decision: 'APPROVE', note: 'Rider handed it in' });
    verify.flush(codPayment('SUCCEEDED'));
    await settle();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminOrderPage);
    root = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  it('confirms cash collection only once the updated payment is on screen (QA-02)', async () => {
    await open(order('DELIVERED'));
    expect(paymentLine()).toContain('COD · PENDING');

    await recordCash();
    await fixture.whenStable();
    // The server accepted it, but the order has not been read again yet: no banner beside the old line.
    expect(banner()).toBeNull();
    expect(paymentLine()).toContain('COD · PENDING');
    expect(button('Saving…').disabled).toBe(true);

    http.expectOne(ORDER_URL).flush(order('DELIVERED', 'SUCCEEDED'));
    await fixture.whenStable();
    expect(banner()).toBe('Cash collection recorded.');
    expect(paymentLine()).toContain('COD · SUCCEEDED');
    expect(root.querySelector('#cash-note')).toBeNull();
    expect(stale()).toBeNull();
  });

  it('shows a fulfilment step together with the new status', async () => {
    await open(order('CONFIRMED'));
    button('Start baking').click();
    const change = http.expectOne(`${ORDER_URL}/status`);
    expect(change.request.body).toEqual({ status: 'PREPARING' });
    change.flush({ ...order('PREPARING') });
    await settle();
    await fixture.whenStable();
    expect(banner()).toBeNull();

    http.expectOne(ORDER_URL).flush(order('PREPARING'));
    await fixture.whenStable();
    expect(banner()).toBe('Now preparing.');
    expect(root.querySelector('[data-testid="status"]')?.textContent).toBe('Preparing');
  });

  it('when the order cannot be read again, says it may be out of date and pauses actions', async () => {
    await open(order('DELIVERED'));
    await recordCash();
    http.expectOne(ORDER_URL).flush(serverError(), { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    // The action did succeed, so it is confirmed, but the old line is flagged and cannot be acted on.
    expect(banner()).toBe('Cash collection recorded.');
    expect(stale()?.textContent).toContain('may be out of date');
    expect(button('Record cash collected').disabled).toBe(true);

    // A reload that fails again keeps everything paused.
    button('Reload').click();
    http.expectOne(ORDER_URL).error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(stale()).not.toBeNull();
    expect(button('Record cash collected').disabled).toBe(true);

    // A successful reload shows the real state and clears the notice.
    button('Reload').click();
    http.expectOne(ORDER_URL).flush(order('DELIVERED', 'SUCCEEDED'));
    await fixture.whenStable();
    expect(stale()).toBeNull();
    expect(paymentLine()).toContain('COD · SUCCEEDED');
    expect(root.querySelector('#cash-note')).toBeNull();
  });

  it('a refused action shows the error and the current order, with no success message', async () => {
    await open(order('DELIVERED'));
    const note = root.querySelector<HTMLInputElement>('#cash-note')!;
    note.value = 'Rider handed it in';
    note.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button('Record cash collected').click();
    http.expectOne('/api/v1/admin/payments/pay1/verification').flush(
      {
        title: 'Conflict',
        status: 409,
        code: 'PAYMENT_ALREADY_DECIDED',
        detail: 'This payment has already been verified.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    http.expectOne(ORDER_URL).flush(order('DELIVERED', 'SUCCEEDED'));
    await fixture.whenStable();

    expect(banner()).toBeNull();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('already been verified');
    expect(paymentLine()).toContain('COD · SUCCEEDED');
    expect(stale()).toBeNull();
  });

  it('does not carry the out-of-date notice over to another order', async () => {
    await open(order('DELIVERED'));
    await recordCash();
    http.expectOne(ORDER_URL).flush(serverError(), { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(stale()).not.toBeNull();

    fixture.componentRef.setInput('orderId', 'o2');
    await settle();
    http
      .expectOne('/api/v1/admin/orders/o2')
      .flush({ ...order('CONFIRMED'), orderId: 'o2', orderNumber: 'BB-20261120-000002' });
    await fixture.whenStable();
    expect(stale()).toBeNull();
    expect(button('Start baking').disabled).toBe(false);
  });
});
