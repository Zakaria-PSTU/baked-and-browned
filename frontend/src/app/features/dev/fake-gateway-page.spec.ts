import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ExternalNavigation } from '../../shared/external-navigation';
import type { FakeSession } from './fake-gateway-api';
import { FakeGatewayPage } from './fake-gateway-page';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const URL = '/api/v1/dev/fake-gateway/sessions/fake_sess_1';
const RETURN = 'http://localhost:8080/api/v1/payments/o1/return';

const session = (status: FakeSession['status'] = 'PENDING'): FakeSession => ({
  sessionId: 'fake_sess_1',
  orderNumber: 'BB-20261118-000001',
  amount: 1310,
  currency: 'BDT',
  status,
  returnUrl: RETURN,
});

describe('FakeGatewayPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async (answer: FakeSession | null) => {
    const go = vi.spyOn(TestBed.inject(ExternalNavigation), 'go').mockReturnValue(undefined);
    const fixture = TestBed.createComponent(FakeGatewayPage);
    fixture.componentRef.setInput('session', 'fake_sess_1');
    await settle();
    const req = http.expectOne(URL);
    if (answer) {
      req.flush(answer);
    } else {
      req.flush(
        { title: 'Not Found', status: 404, code: 'SESSION_NOT_FOUND' },
        { status: 404, statusText: 'Not Found' },
      );
    }
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const click = async (testId: string) => {
      root.querySelector<HTMLElement>(`[data-testid="${testId}"]`)!.click();
      await fixture.whenStable();
    };
    return { fixture, root, click, go };
  };

  it('pays and sends the browser to the return address', async () => {
    const { fixture, root, click, go } = await open(session());
    expect(root.querySelector('[data-testid="order-number"]')?.textContent).toContain(
      'BB-20261118-000001',
    );

    await click('pay');
    const req = http.expectOne(`${URL}/finish`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ outcome: 'SUCCEEDED', notifyShop: true });
    req.flush(session('SUCCEEDED'));
    await fixture.whenStable();
    expect(go).toHaveBeenCalledWith(RETURN);
  });

  it('can fail a payment with its callback lost', async () => {
    const { fixture, root, click, go } = await open(session());
    const notify = root.querySelector<HTMLInputElement>('[data-testid="notify-shop"]')!;
    notify.checked = false;
    notify.dispatchEvent(new Event('change'));

    await click('fail');
    const req = http.expectOne(`${URL}/finish`);
    expect(req.request.body).toEqual({ outcome: 'FAILED', notifyShop: false });
    req.flush(session('FAILED'));
    await fixture.whenStable();
    expect(go).toHaveBeenCalledWith(RETURN);
  });

  it('shows why finishing was refused and stays on the page', async () => {
    const { fixture, root, click, go } = await open(session());
    await click('cancel');
    http.expectOne(`${URL}/finish`).flush(
      {
        title: 'Conflict',
        status: 409,
        code: 'SESSION_FINISHED',
        detail: 'This payment page has already been finished.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(root.querySelector('[data-testid="finish-error"]')?.textContent).toContain(
      'already been finished',
    );
    expect(go).not.toHaveBeenCalled();
  });

  it('offers only the way back once the payment has ended', async () => {
    const { root, click, go } = await open(session('CANCELLED'));
    expect(root.querySelector('[data-testid="pay"]')).toBeNull();
    expect(root.querySelector('[data-testid="finished"]')?.textContent).toContain('cancelled');
    await click('return');
    expect(go).toHaveBeenCalledWith(RETURN);
  });

  it('says so when the session is unknown', async () => {
    const { root } = await open(null);
    expect(root.querySelector('[data-testid="load-error"]')).not.toBeNull();
  });
});
