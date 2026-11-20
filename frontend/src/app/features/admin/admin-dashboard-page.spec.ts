import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { DashboardSummary } from './admin-dashboard-api';
import { AdminDashboardPage } from './admin-dashboard-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const URL = '/api/v1/admin/dashboard/summary';

describe('AdminDashboardPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(body: DashboardSummary) {
    const fixture = TestBed.createComponent(AdminDashboardPage);
    await settle();
    http.expectOne(URL).flush(body);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const values = (el: HTMLElement) =>
    [...el.querySelectorAll('[data-testid="tile-value"]')].map((v) => v.textContent?.trim());

  it('shows the figures as the server sends them, formatted in BDT', async () => {
    const el = await render({
      ordersToday: 3,
      revenueToday: 125000,
      pendingOrders: 2,
      preparingOrders: 1,
      collectedToday: 1310,
      awaitingPaymentVerification: 4,
      openReconciliationCases: 0,
      lowStockIngredients: 1,
      refundsToProcess: 2,
    });

    expect(values(el)).toEqual(['3', '৳1,25,000.00', '৳1,310.00', '2', '1', '4', '0', '2', '1']);
    const flagged = [...el.querySelectorAll('[data-attention="true"]')].map(
      (t) => t.querySelector('p')?.textContent,
    );
    expect(flagged).toEqual(['Payments to verify', 'Refunds', 'Low stock']);
    // Only queues with a screen link to it.
    const links = [...el.querySelectorAll('li a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual([
      '/admin/orders?status=CONFIRMED',
      '/admin/orders?status=PREPARING',
      '/admin/payments',
      '/admin/reconciliation',
      '/admin/refunds',
    ]);
  });

  it('treats the optional figures as zero when absent', async () => {
    const el = await render({
      ordersToday: 0,
      revenueToday: 0,
      pendingOrders: 0,
      preparingOrders: 0,
    });

    expect(values(el)).toEqual(['0', '৳0.00', '৳0.00', '0', '0', '0', '0', '0', '0']);
    expect(el.querySelectorAll('[data-attention="true"]').length).toBe(0);
  });

  it('reports a failed load and can refresh', async () => {
    const fixture = TestBed.createComponent(AdminDashboardPage);
    await settle();
    http.expectOne(URL).flush(null, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('could not load');

    el.querySelector('button')?.click();
    await settle();
    http
      .expectOne(URL)
      .flush({ ordersToday: 1, revenueToday: 660, pendingOrders: 1, preparingOrders: 0 });
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(values(el)[0]).toBe('1');
  });
});
