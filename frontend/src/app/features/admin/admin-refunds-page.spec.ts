import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import type { Refund } from '../../core/api/generated/models';
import { AuthSession } from '../../core/auth/auth-session';
import { requireAdmin } from '../../core/auth/auth.guards';
import { AdminRefundsPage } from './admin-refunds-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const LIST = '/api/v1/admin/refunds';
const ME = 'admin-me';

const refund = (overrides: Partial<Refund> = {}): Refund => ({
  id: 'r1',
  paymentId: 'pay1',
  orderId: 'o1',
  orderNumber: 'BB-20261120-000001',
  amount: 1310,
  currency: 'BDT',
  status: 'REQUESTED',
  requestReason: 'Cake arrived damaged',
  requestedByType: 'ADMIN',
  requestedBy: 'admin-other',
  createdAt: '2026-11-20T08:00:00Z',
  ...overrides,
});

const problem = (status: number, code: string, detail: string) => ({
  body: { title: 'Problem', status, code, detail },
  init: { status, statusText: 'Problem' },
});

describe('AdminRefundsPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminRefundsPage>;
  let root: HTMLElement;

  const button = (label: string, within: ParentNode = root) => {
    const found = [...within.querySelectorAll('button')].find((b) =>
      b.textContent?.includes(label),
    );
    if (!found) {
      throw new Error(`no button "${label}"`);
    }
    return found;
  };
  const cards = (section: string) => [
    ...root.querySelectorAll(`[data-testid="${section}"] [data-testid="refund-card"]`),
  ];
  const alert = () => root.querySelector('[role="alert"]')?.textContent?.trim() ?? '';
  const status = () => root.querySelector('[role="status"]')?.textContent?.trim() ?? null;

  /** The page's three reads: each queue by status, and the newest refunds for the history. */
  const queue = (status: 'REQUESTED' | 'APPROVED') =>
    http.expectOne((r) => r.url === LIST && r.params.get('status') === status);

  function answerLists(refunds: Refund[], recent: Refund[] = refunds): void {
    queue('REQUESTED').flush(refunds.filter((r) => r.status === 'REQUESTED'));
    queue('APPROVED').flush(refunds.filter((r) => r.status === 'APPROVED'));
    http.expectOne(LIST).flush(recent);
  }

  async function show(refunds: Refund[]): Promise<void> {
    await settle();
    answerLists(refunds);
    await fixture.whenStable();
  }

  async function type(selector: string, value: string): Promise<void> {
    const field = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector);
    if (!field) {
      throw new Error(`no field ${selector}`);
    }
    field.value = value;
    field.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function click(label: string): Promise<void> {
    button(label).click();
    await fixture.whenStable();
  }

  async function reloaded(refunds: Refund[] = []): Promise<void> {
    await settle();
    answerLists(refunds);
    await fixture.whenStable();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthSession).start('token', 900, {
      id: ME,
      fullName: 'Admin Me',
      phone: '+8801700000000',
      role: 'ADMIN',
    });
    fixture = TestBed.createComponent(AdminRefundsPage);
    root = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  it('is an admin-only route', () => {
    expect(routes.find((r) => r.path === 'admin/refunds')?.canMatch).toEqual([requireAdmin]);
  });

  it('sorts refunds into to approve, to pay out and recently finished', async () => {
    await show([
      refund({
        id: 'r4',
        status: 'COMPLETED',
        externalReference: 'BKASH-RF-1',
        completedAt: '2026-11-20T09:00:00Z',
      }),
      refund({
        id: 'r3',
        status: 'APPROVED',
        orderNumber: 'BB-20261120-000003',
        createdAt: '2026-11-20T07:00:00Z',
      }),
      refund({
        id: 'r2',
        orderNumber: 'BB-20261120-000002',
        createdAt: '2026-11-20T08:30:00Z',
        requestedBy: ME,
      }),
      refund({
        id: 'r1',
        createdAt: '2026-11-20T06:00:00Z',
        requestedByType: 'CUSTOMER',
        requestedBy: 'customer-1',
      }),
      refund({
        id: 'r5',
        status: 'REJECTED',
        orderNumber: 'BB-20261120-000005',
        decidedBy: ME,
        decisionNote: 'Delivered fine',
      }),
      refund({
        id: 'r6',
        status: 'REJECTED',
        orderNumber: 'BB-20261120-000006',
        requestedBy: 'admin-other',
        decidedBy: 'admin-other',
        decisionNote: 'Asked by mistake',
      }),
    ]);

    const toApprove = cards('to-approve');
    // Oldest first.
    expect(toApprove.map((c) => c.querySelector('h3')?.textContent?.trim())).toEqual([
      'Order BB-20261120-000001',
      'Order BB-20261120-000002',
    ]);
    expect(toApprove[0].querySelector('[data-testid="refund-requester"]')?.textContent).toContain(
      'the customer, by cancelling',
    );
    expect(toApprove[0].textContent).toContain('Fri, 20 Nov, 12:00 pm');
    expect(toApprove[0].querySelector('[data-testid="refund-amount"]')?.textContent).toContain(
      '৳1,310.00',
    );
    expect(
      toApprove[0].querySelector('[data-testid="refund-order-link"]')?.getAttribute('href'),
    ).toBe('/admin/orders/o1');
    expect(toApprove[1].querySelector('[data-testid="refund-requester"]')?.textContent).toContain(
      'you',
    );
    expect(cards('to-pay-out').length).toBe(1);
    // Finished ones: sent, rejected by a reviewer, and withdrawn by the admin who asked.
    const finished = [...root.querySelectorAll('[data-testid="history"] li')].map(
      (li) => li.textContent?.replace(/\s+/g, ' ') ?? '',
    );
    expect(finished.length).toBe(3);
    expect(finished[0]).toContain('Sent · BKASH-RF-1');
    expect(finished[1]).toContain('Order BB-20261120-000005 · Rejected');
    expect(finished[2]).toContain('Order BB-20261120-000006 · Withdrawn');
  });

  it('shows an old open refund that newer ones pushed out of the newest list', async () => {
    const old = refund({ id: 'r-old', createdAt: '2026-01-05T08:00:00Z' });
    const due = refund({ id: 'r-due', status: 'APPROVED', createdAt: '2026-02-01T08:00:00Z' });
    await settle();
    // The newest refunds no longer include them; the status reads still do.
    answerLists([old, due], []);
    await fixture.whenStable();

    expect(cards('to-approve').length).toBe(1);
    expect(cards('to-pay-out').length).toBe(1);
    expect(root.querySelector('[data-testid="history"]')).toBeNull();
  });

  it('approves another admin’s request with a note, after saying no money moves yet', async () => {
    await show([refund()]);

    await click('Approve');
    expect(alert()).toContain('Write a note');
    http.expectNone(`${LIST}/r1/decision`);

    await type('#refund-note-r1', '  Photos of the damage checked ');
    await click('Approve');
    expect(root.querySelector('[data-testid="refund-confirm"]')?.textContent).toContain(
      'No money moves yet',
    );
    await click('Back');
    http.expectNone(`${LIST}/r1/decision`);

    await click('Approve');
    // A double click sends one decision.
    button('Yes, approve').click();
    button('Yes, approve').click();
    const req = http.expectOne(`${LIST}/r1/decision`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ decision: 'APPROVE', note: 'Photos of the damage checked' });
    req.flush(refund({ status: 'APPROVED' }));
    await reloaded([refund({ status: 'APPROVED' })]);

    expect(status()).toContain('is approved. Send the money, then record its reference.');
    expect(cards('to-approve').length).toBe(0);
    expect(cards('to-pay-out').length).toBe(1);
  });

  it('does not let the requester approve, only withdraw', async () => {
    await show([refund({ requestedBy: ME })]);

    expect(root.querySelector('[data-testid="own-request"]')?.textContent).toContain(
      'another admin must approve it',
    );
    expect(button('Approve').disabled).toBe(true);

    await type('#refund-note-r1', 'Asked by mistake');
    await click('Withdraw');
    button('Yes, withdraw').click();
    const req = http.expectOne(`${LIST}/r1/decision`);
    expect(req.request.body).toEqual({ decision: 'REJECT', note: 'Asked by mistake' });
    req.flush(refund({ status: 'REJECTED' }));
    await reloaded();
    expect(status()).toContain('Your refund request for order BB-20261120-000001 is withdrawn.');
  });

  it('explains a refusal of a self-approval the page could not foresee', async () => {
    // For example the session's user id is not the one the server knows.
    await show([refund()]);
    await type('#refund-note-r1', 'Looks right');
    await click('Approve');
    button('Yes, approve').click();
    const refused = problem(
      409,
      'SECOND_APPROVER_REQUIRED',
      'Another admin must approve this refund.',
    );
    http.expectOne(`${LIST}/r1/decision`).flush(refused.body, refused.init);
    await fixture.whenStable();

    expect(alert()).toBe('You requested this refund, so another admin must approve it.');
    expect(cards('to-approve').length).toBe(1);
  });

  it('records money already sent only with a reference, saying it sends nothing', async () => {
    await show([refund({ status: 'APPROVED', decisionNote: 'Approved' })]);

    await click('Record refund sent');
    expect(alert()).toContain('Enter the refund reference');
    await type('#refund-ref-r1', ' BKASH-RF-77120 ');
    await click('Record refund sent');
    expect(root.querySelector('[data-testid="refund-confirm"]')?.textContent).toContain(
      'This does not send money',
    );

    // A double click records the payout once.
    button('Yes, the money was sent').click();
    button('Yes, the money was sent').click();
    const req = http.expectOne(`${LIST}/r1/completion`);
    expect(req.request.body).toEqual({ externalReference: 'BKASH-RF-77120' });
    req.flush(refund({ status: 'COMPLETED', externalReference: 'BKASH-RF-77120' }));
    await reloaded([refund({ status: 'COMPLETED', externalReference: 'BKASH-RF-77120' })]);

    expect(status()).toContain('recorded as sent; the customer is told by SMS');
    expect(root.querySelector('[data-testid="history"]')?.textContent).toContain('BKASH-RF-77120');
  });

  it('refreshes when someone else handled the refund first', async () => {
    await show([refund()]);
    await type('#refund-note-r1', 'Rejecting');
    await click('Reject');
    button('Yes, reject').click();
    const done = problem(
      409,
      'REFUND_ALREADY_DECIDED',
      'A decision has already been recorded for this refund.',
    );
    http.expectOne(`${LIST}/r1/decision`).flush(done.body, done.init);
    await reloaded([refund({ status: 'APPROVED' })]);

    expect(status()).toContain('already handled by someone else');
    expect(cards('to-pay-out').length).toBe(1);
  });

  it('shows a refused account and a lost connection without changing anything', async () => {
    await show([refund({ status: 'APPROVED' })]);
    await type('#refund-ref-r1', 'BANK-1');
    await click('Record refund sent');
    button('Yes, the money was sent').click();
    const forbidden = problem(
      403,
      'FORBIDDEN',
      'You do not have permission to perform this action.',
    );
    http.expectOne(`${LIST}/r1/completion`).flush(forbidden.body, forbidden.init);
    await fixture.whenStable();
    expect(alert()).toBe('You do not have permission to perform this action.');

    await click('Record refund sent');
    button('Yes, the money was sent').click();
    http.expectOne(`${LIST}/r1/completion`).error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(alert()).toContain('Cannot reach Baked & Browned');
    expect(cards('to-pay-out').length).toBe(1);
    expect(status()).toBeNull();
  });

  it('reports a failed load, and empty queues', async () => {
    await settle();
    // One failed read is enough to show the page as not loaded.
    queue('APPROVED').flush([]);
    http.expectOne(LIST).flush([]);
    queue('REQUESTED').flush(
      { title: 'Forbidden', status: 403 },
      { status: 403, statusText: 'Forbidden' },
    );
    await fixture.whenStable();
    expect(alert()).toContain('could not load the refunds');
    expect(cards('to-approve').length).toBe(0);

    // Not click(): the page would not settle while the reload is still unanswered.
    button('Refresh').click();
    await reloaded([]);
    expect(root.querySelector('[data-testid="none-to-approve"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="none-to-pay-out"]')).not.toBeNull();
  });
});
