import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import { requireAdmin } from '../../core/auth/auth.guards';
import type { ReconciliationCase } from '../../core/api/generated/models';
import { AdminReconciliationPage } from './admin-reconciliation-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const LIST = '/api/v1/admin/payment-reconciliation-cases';
const resolution = (id: string) => `${LIST}/${id}/resolution`;

const lateCase = (overrides: Partial<ReconciliationCase> = {}): ReconciliationCase => ({
  id: 'c1',
  paymentId: 'pay1',
  orderId: 'o1',
  orderNumber: 'BB-20261120-000007',
  caseType: 'LATE_SUCCESS',
  reason: 'PAID_AFTER_ORDER_EXPIRED',
  status: 'OPEN',
  createdAt: '2026-11-20T08:05:00Z',
  ...overrides,
});

const conflict = (code: string, detail: string) => ({
  body: { title: 'Conflict', status: 409, code, detail },
  init: { status: 409, statusText: 'Conflict' },
});

describe('AdminReconciliationPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminReconciliationPage>;
  let root: HTMLElement;

  const cards = () => [...root.querySelectorAll('[data-testid="case-card"]')];
  const button = (label: string, within: ParentNode = root) => {
    const found = [...within.querySelectorAll('button')].find((b) =>
      b.textContent?.includes(label),
    );
    if (!found) {
      throw new Error(`no button "${label}"`);
    }
    return found;
  };
  const alert = () => root.querySelector('[role="alert"]')?.textContent?.trim() ?? '';
  const status = () => root.querySelector('[role="status"]')?.textContent?.trim() ?? null;

  const list = () => {
    const req = http.expectOne((r) => r.url === LIST);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('OPEN');
    return req;
  };

  async function show(cases: ReconciliationCase[]): Promise<void> {
    await settle();
    list().flush(cases);
    await fixture.whenStable();
  }

  async function writeNote(text: string): Promise<void> {
    const note = root.querySelector<HTMLTextAreaElement>('textarea');
    if (!note) {
      throw new Error('no note box');
    }
    note.value = text;
    note.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  /** Picks an action and confirms it; returns the request sent. */
  async function resolveWith(action: string, confirm: string) {
    button(action).click();
    await fixture.whenStable();
    button(confirm).click();
    const req = http.expectOne(resolution('c1'));
    expect(req.request.method).toBe('POST');
    return req;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminReconciliationPage);
    root = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  it('is an admin-only route', () => {
    const route = routes.find((r) => r.path === 'admin/reconciliation');
    expect(route?.canMatch).toEqual([requireAdmin]);
  });

  it('lists open cases with what happened, when, and a link to the order', async () => {
    await show([
      lateCase(),
      lateCase({
        id: 'c2',
        orderId: 'o2',
        orderNumber: 'BB-20261120-000008',
        caseType: 'AMBIGUOUS_STATUS',
        reason: 'GATEWAY_STATUS_UNKNOWN',
      }),
      lateCase({ id: 'c3', caseType: 'AMOUNT_MISMATCH', reason: null }),
    ]);

    expect(cards().length).toBe(3);
    const first = cards()[0];
    expect(first.querySelector('h2')?.textContent).toContain('Order BB-20261120-000007');
    expect(first.querySelector('[data-testid="case-type"]')?.textContent).toContain(
      'Paid after the order ended',
    );
    expect(first.querySelector('[data-testid="case-reason"]')?.textContent).toContain(
      'arrived after the order expired',
    );
    expect(first.textContent).toContain('Fri, 20 Nov, 2:05 pm');
    expect(first.querySelector('[data-testid="order-link"]')?.getAttribute('href')).toBe(
      '/admin/orders/o1',
    );
    expect(cards()[1].querySelector('[data-testid="case-reason"]')?.textContent).toContain(
      'never confirmed',
    );
    expect(cards()[2].querySelector('[data-testid="case-reason"]')).toBeNull();
  });

  it('shows an empty queue, and a failed load', async () => {
    await show([]);
    expect(root.querySelector('[data-testid="empty"]')?.textContent).toContain('No open cases');

    button('Refresh').click();
    await settle();
    list().flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(alert()).toContain('could not load the cases');
  });

  it('reinstates only with a note, after showing what it does', async () => {
    await show([lateCase()]);

    button('Reinstate order').click();
    await fixture.whenStable();
    expect(root.querySelector('[data-testid="confirm-panel"]')?.textContent).toContain(
      'only if they are still available',
    );
    // Back out without sending anything.
    button('Back').click();
    await fixture.whenStable();
    http.expectNone(resolution('c1'));

    button('Reinstate order').click();
    await fixture.whenStable();
    button('Yes, reinstate the order').click();
    await fixture.whenStable();
    http.expectNone(resolution('c1'));
    expect(alert()).toContain('Write a note');

    // The choice stays open: add the note and confirm.
    await writeNote('  Customer still wants it; slot has room. ');
    expect(alert()).toBe('');
    button('Yes, reinstate the order').click();
    const req = http.expectOne(resolution('c1'));
    expect(req.request.body).toEqual({
      action: 'REINSTATE',
      note: 'Customer still wants it; slot has room.',
    });
    req.flush(lateCase({ status: 'RESOLVED', resolution: 'REINSTATED' }));
    await settle();
    list().flush([]);
    await fixture.whenStable();
    expect(status()).toBe('Order BB-20261120-000007 is confirmed again.');
    expect(cards().length).toBe(0);
  });

  it('keeps the case and suggests a refund when the slot or stock is gone', async () => {
    await show([lateCase()]);
    await writeNote('Try to reinstate');
    const slot = conflict(
      'SLOT_UNAVAILABLE',
      "The order's delivery slot has started, closed or filled up; it cannot be reinstated.",
    );
    (await resolveWith('Reinstate order', 'Yes, reinstate the order')).flush(slot.body, slot.init);
    await fixture.whenStable();
    expect(alert()).toContain('cannot be reinstated');
    expect(alert()).toContain('Nothing was changed. You can request a refund instead.');
    expect(cards().length).toBe(1);
    expect(status()).toBeNull();

    const stock = conflict('INSUFFICIENT_STOCK', 'Not enough stock to reinstate this order.');
    (await resolveWith('Reinstate order', 'Yes, reinstate the order')).flush(
      stock.body,
      stock.init,
    );
    await fixture.whenStable();
    expect(alert()).toContain('You can request a refund instead.');
    // The note is kept for the next try.
    expect(root.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('Try to reinstate');
  });

  it('says plainly that requesting a refund is not a refund', async () => {
    await show([lateCase()]);
    await writeNote('Slot gone; refund in full.');
    button('Request refund').click();
    await fixture.whenStable();
    const effect = root.querySelector('[data-testid="confirm-panel"]')?.textContent ?? '';
    expect(effect).toContain('This is not a refund and no money is sent');
    expect(effect).toContain('another admin must approve');

    button('Yes, request a refund').click();
    const req = http.expectOne(resolution('c1'));
    expect(req.request.body).toEqual({
      action: 'REQUEST_REFUND',
      note: 'Slot gone; refund in full.',
    });
    req.flush(lateCase({ status: 'RESOLVED', resolution: 'REFUND_REQUESTED' }));
    await settle();
    list().flush([]);
    await fixture.whenStable();
    expect(status()).toBe(
      'A refund was requested for order BB-20261120-000007. It is not paid yet: another admin must approve it first.',
    );
  });

  it('closes a case without action', async () => {
    await show([lateCase()]);
    await writeNote('Customer paid twice by mistake; settled with them.');
    const req = await resolveWith('Close without action', 'Yes, close the case');
    expect(req.request.body).toMatchObject({ action: 'NO_ACTION' });
    req.flush(lateCase({ status: 'RESOLVED', resolution: 'NO_ACTION' }));
    await settle();
    list().flush([]);
    await fixture.whenStable();
    expect(status()).toBe('The case for order BB-20261120-000007 is closed without changes.');
  });

  it('refreshes the list when someone else resolved the case first', async () => {
    await show([lateCase()]);
    await writeNote('Closing it');
    const done = conflict('CASE_ALREADY_RESOLVED', 'This case has already been resolved.');
    (await resolveWith('Close without action', 'Yes, close the case')).flush(done.body, done.init);
    await settle();
    list().flush([]);
    await fixture.whenStable();
    expect(status()).toContain('was already resolved');
    expect(cards().length).toBe(0);
  });

  it('shows a refusal of the admin role and a lost connection without changing anything', async () => {
    await show([lateCase()]);
    await writeNote('Closing it');
    (await resolveWith('Close without action', 'Yes, close the case')).flush(
      { title: 'Forbidden', status: 403, detail: 'You do not have access to this.' },
      { status: 403, statusText: 'Forbidden' },
    );
    await fixture.whenStable();
    expect(alert()).toContain('You do not have access to this.');

    (await resolveWith('Close without action', 'Yes, close the case')).error(
      new ProgressEvent('error'),
    );
    await fixture.whenStable();
    expect(alert()).toContain('Cannot reach Baked & Browned');
    expect(cards().length).toBe(1);
    expect(status()).toBeNull();
  });
});
