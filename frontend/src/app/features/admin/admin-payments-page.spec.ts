import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { AdminPayment, AdminPaymentPage } from '../../core/api/generated/models';
import { AdminPaymentsPage } from './admin-payments-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const payment = (overrides: Partial<AdminPayment> = {}): AdminPayment => ({
  id: 'pay1',
  orderId: 'o1',
  orderNumber: 'BB-20261118-000007',
  method: 'MANUAL',
  amount: 4490,
  currency: 'BDT',
  status: 'PENDING',
  createdAt: '2026-11-18T08:00:00Z',
  latestSubmission: {
    id: 'm1',
    paymentId: 'pay1',
    provider: 'BKASH',
    transactionReference: '8AB3 KQ9Z 21',
    status: 'SUBMITTED',
    hasProof: true,
    createdAt: '2026-11-18T08:05:00Z',
  },
  ...overrides,
});

const page = (content: AdminPayment[], totalElements = content.length): AdminPaymentPage => ({
  content,
  page: 0,
  size: 20,
  totalElements,
});

describe('AdminPaymentsPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminPaymentsPage>;
  let root: HTMLElement;

  const button = (label: string) => {
    const found = [...root.querySelectorAll('button')].find((b) => b.textContent?.includes(label));
    if (!found) {
      throw new Error(`no button "${label}"`);
    }
    return found;
  };
  const alert = () => root.querySelector('[role="alert"]')?.textContent ?? '';
  const writeNote = async (text: string) => {
    const note = root.querySelector<HTMLTextAreaElement>('textarea');
    if (!note) {
      throw new Error('no note box');
    }
    note.value = text;
    note.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  };
  const list = () => {
    const req = http.expectOne((r) => r.url === '/api/v1/admin/payments');
    expect(req.request.params.get('awaitingVerification')).toBe('true');
    return req;
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminPaymentsPage);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('approves a transfer only with a note of what was checked', async () => {
    const req = list();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(page([payment()]));
    await fixture.whenStable();

    const card = root.querySelector('[data-testid="payment-card"]')?.textContent ?? '';
    expect(card).toContain('Order BB-20261118-000007');
    expect(card).toContain('৳4,490.00');
    expect(card).toContain('bKash');
    expect(root.querySelector('[data-testid="reference"]')?.textContent?.trim()).toBe(
      '8AB3 KQ9Z 21',
    );
    expect(card).toContain('Wed, 18 Nov, 2:05 pm');

    button('Approve').click();
    await fixture.whenStable();
    expect(alert()).toContain('Write what you checked');

    await writeNote('  bKash statement 18 Nov 14:05, 4490 ');
    button('Approve').click();
    const verify = http.expectOne('/api/v1/admin/payments/pay1/verification');
    expect(verify.request.method).toBe('POST');
    expect(verify.request.body).toEqual({
      decision: 'APPROVE',
      note: 'bKash statement 18 Nov 14:05, 4490',
    });
    verify.flush(payment({ status: 'SUCCEEDED' }));
    await settle();

    list().flush(page([]));
    await fixture.whenStable();
    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      'Order BB-20261118-000007 is confirmed.',
    );
    expect(root.querySelector('[data-testid="empty"]')).not.toBeNull();
  });

  it('asks again before rejecting, and shows a refusal from the server', async () => {
    list().flush(page([payment()]));
    await fixture.whenStable();
    await writeNote('No such transaction on the statement');

    button('Reject').click();
    await fixture.whenStable();
    // Nothing is sent until the second click.
    http.expectNone('/api/v1/admin/payments/pay1/verification');
    button('Keep it').click();
    await fixture.whenStable();
    button('Reject').click();
    await fixture.whenStable();
    button('Yes, reject and cancel the order').click();

    const verify = http.expectOne('/api/v1/admin/payments/pay1/verification');
    expect(verify.request.body).toMatchObject({ decision: 'REJECT' });
    verify.flush(
      {
        title: 'Conflict',
        status: 409,
        code: 'PAYMENT_ALREADY_DECIDED',
        detail: 'This payment has already been verified.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(alert()).toContain('already been verified');
    // Back to the first step after a failure.
    expect(button('Reject').textContent?.trim()).toBe('Reject');
  });

  it('shows image proof inline and downloads PDF proof, accepting every proof type', async () => {
    const createUrl = vi.fn(() => 'blob:proof-1');
    Object.defineProperty(URL, 'createObjectURL', { value: createUrl, configurable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true });
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockReturnValue(undefined);

    list().flush(
      page([
        payment(),
        payment({ id: 'pay2', orderNumber: 'BB-20261118-000008' }),
        payment({
          id: 'pay3',
          latestSubmission: { ...payment().latestSubmission!, hasProof: false },
        }),
      ]),
    );
    await fixture.whenStable();
    const cards = root.querySelectorAll('[data-testid="payment-card"]');
    expect(cards[2].textContent).toContain('No proof file attached.');

    (cards[0].querySelector('button') as HTMLButtonElement).click();
    const image = http.expectOne('/api/v1/admin/payments/pay1/proof');
    expect(image.request.headers.get('Accept')).toBe('image/jpeg, image/png, application/pdf');
    image.flush(new Blob([new Uint8Array([0x89, 0x50])], { type: 'image/png' }));
    await fixture.whenStable();
    expect(root.querySelector<HTMLImageElement>('[data-testid="proof-image"]')?.src).toBe(
      'blob:proof-1',
    );

    (cards[1].querySelector('button') as HTMLButtonElement).click();
    http
      .expectOne('/api/v1/admin/payments/pay2/proof')
      .flush(new Blob(['%PDF-1.7'], { type: 'application/pdf' }));
    await fixture.whenStable();
    expect(clicked).toHaveBeenCalledTimes(1);
    const link = clicked.mock.contexts[0] as HTMLAnchorElement;
    expect(link.download).toBe('payment-proof-BB-20261118-000008.pdf');
    expect(root.querySelectorAll('[data-testid="proof-image"]').length).toBe(1);

    fixture.destroy();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:proof-1');
  });

  it('moves back a page when the last item on the last page is decided', async () => {
    list().flush(page([payment()], 21));
    await fixture.whenStable();
    button('Next').click();
    await settle();
    const second = list();
    expect(second.request.params.get('page')).toBe('1');
    second.flush({ ...page([payment({ id: 'pay21' })], 21), page: 1 });
    await fixture.whenStable();

    await writeNote('Checked statement');
    button('Approve').click();
    http
      .expectOne('/api/v1/admin/payments/pay21/verification')
      .flush(payment({ status: 'SUCCEEDED' }));
    await settle();
    const back = list();
    expect(back.request.params.get('page')).toBe('0');
    back.flush(page([payment()], 20));
    await fixture.whenStable();
  });
});
