import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Order } from '../../core/api/generated/models';
import { LISTED, MyOrdersPage } from './my-orders-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const order = (n: number, overrides: Partial<Order> = {}): Order => ({
  orderId: `o${n}`,
  orderNumber: `BB-20261118-00000${n}`,
  status: 'CONFIRMED',
  paymentStatus: 'PENDING',
  paymentMethod: 'COD',
  currency: 'BDT',
  subtotal: 1250,
  deliveryFee: 60,
  discount: 0,
  total: 1310,
  createdAt: '2026-11-18T08:05:00Z',
  ...overrides,
});

describe('MyOrdersPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (orders: Order[]) => {
    const fixture = TestBed.createComponent(MyOrdersPage);
    await settle();
    http.expectOne('/api/v1/orders').flush(orders);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  };

  it('lists orders in progress before past ones, keeping the server order in each', async () => {
    const root = await open([
      order(4, { status: 'PENDING_PAYMENT', paymentMethod: 'MANUAL', total: 4490 }),
      order(3, { status: 'DELIVERED' }),
      order(2, { status: 'PREPARING' }),
      order(1, { status: 'CANCELLED' }),
    ]);

    const rows = (selector: string) =>
      [...root.querySelectorAll(`${selector} [data-testid="order-row"]`)].map((r) => ({
        text: r.textContent?.replace(/\s+/g, ' ').trim(),
        href: r.querySelector('a')?.getAttribute('href'),
      }));
    expect(rows('[data-testid="current"]').map((r) => r.href)).toEqual([
      '/orders/o4',
      '/orders/o2',
    ]);
    expect(rows('[data-testid="past"]').map((r) => r.href)).toEqual(['/orders/o3', '/orders/o1']);
    const first = rows('[data-testid="current"]')[0].text ?? '';
    expect(first).toContain('Order BB-20261118-000004');
    expect(first).toContain('Placed Wed, 18 Nov, 2:05 pm');
    expect(first).toContain('Waiting for payment');
    expect(first).toContain('৳4,490.00');
    expect(rows('[data-testid="past"]')[1].text).toContain('Cancelled');
    expect(root.textContent).not.toContain('latest');
  });

  it('invites a first order when there are none', async () => {
    const root = await open([]);
    expect(root.querySelector('[data-testid="no-orders"]')?.textContent).toContain(
      'You have not ordered anything yet.',
    );
  });

  it('says the list is cut off when the server returned its maximum', async () => {
    const root = await open(
      Array.from({ length: LISTED }, (_, i) => order(i, { orderId: `o${i}` })),
    );
    expect(root.textContent).toContain(`Showing your latest ${LISTED} orders only.`);
  });

  it('reports a failed load', async () => {
    const fixture = TestBed.createComponent(MyOrdersPage);
    await settle();
    http
      .expectOne('/api/v1/orders')
      .flush({ title: 'Error', status: 500 }, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();
  });
});
