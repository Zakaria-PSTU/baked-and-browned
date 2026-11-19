import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { OrderDetail } from '../../core/api/generated/models';
import { PAYMENT_POLLING, PaymentReturnPage } from './payment-return-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const online = (
  status: OrderDetail['status'],
  payment: OrderDetail['payment']['status'],
): OrderDetail => ({
  orderId: 'o1',
  orderNumber: 'BB-20261118-000001',
  status,
  paymentStatus: payment,
  paymentMethod: 'ONLINE',
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
  payment: { method: 'ONLINE', status: payment },
  statusHistory: [],
  cancellationDeadline: '2026-11-19T10:00:00Z',
  cancellable: true,
});

describe('PaymentReturnPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PAYMENT_POLLING, useValue: { intervalMs: 0, attempts: 3 } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(PaymentReturnPage);
    fixture.componentRef.setInput('orderId', 'o1');
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    /** Answers the next poll of the order. */
    const answer = async (order: OrderDetail) => {
      await settle();
      http.expectOne('/api/v1/orders/o1').flush(order);
      await settle();
      await fixture.whenStable();
    };
    return { fixture, root, navigate, answer };
  };

  it('keeps checking until the verified outcome, then shows the order', async () => {
    const { root, navigate, answer } = open();
    await answer(online('PENDING_PAYMENT', 'PENDING'));
    expect(root.querySelector('[data-testid="checking"]')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();

    await answer(online('CONFIRMED', 'SUCCEEDED'));
    expect(navigate).toHaveBeenCalledWith(['/orders', 'o1'], { replaceUrl: true });
  });

  it('shows the order at once after a failed attempt, so the customer can retry', async () => {
    const { navigate, answer } = open();
    await answer(online('PENDING_PAYMENT', 'FAILED'));
    expect(navigate).toHaveBeenCalledWith(['/orders', 'o1'], { replaceUrl: true });
  });

  it('says the payment is still being confirmed when no outcome arrives in time', async () => {
    const { root, navigate, answer } = open();
    for (let i = 0; i < 3; i++) {
      await answer(online('PENDING_PAYMENT', 'PENDING'));
    }
    expect(navigate).not.toHaveBeenCalled();
    expect(root.querySelector('[data-testid="slow"]')?.textContent).toContain('do not');
    expect(root.querySelector('[data-testid="view-order"]')?.getAttribute('href')).toBe(
      '/orders/o1',
    );
  });

  it('points to the order when it cannot be read', async () => {
    const { fixture, root } = open();
    await settle();
    http.expectOne('/api/v1/orders/o1').flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    await fixture.whenStable();
    expect(root.querySelector('[data-testid="error"]')).not.toBeNull();
  });
});
