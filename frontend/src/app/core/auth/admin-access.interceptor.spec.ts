import {
  HttpClient,
  HttpErrorResponse,
  HttpXsrfTokenExtractor,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AdminAccess } from './admin-access';
import { adminAccessInterceptor } from './admin-access.interceptor';
import { authInterceptor } from './auth.interceptor';
import { AuthSession } from './auth-session';
import { provideHttpClientRefreshTransport } from './refresh-transport.testing';

const admin = { id: 'u1', fullName: 'Rafiq', phone: '+8801712345678', role: 'ADMIN' as const };
const forbidden = {
  body: {
    title: 'Forbidden',
    status: 403,
    code: 'FORBIDDEN',
    detail: 'You do not have permission to perform this action.',
  },
  init: { status: 403, statusText: 'Forbidden' },
};
const ME = '/api/v1/auth/me';

describe('adminAccessInterceptor (F-2)', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let session: AuthSession;
  let access: AdminAccess;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor, adminAccessInterceptor])),
        provideHttpClientTesting(),
        provideHttpClientRefreshTransport(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'csrf-1' } },
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(AuthSession);
    access = TestBed.inject(AdminAccess);
    const router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigate');
    vi.spyOn(router, 'navigateByUrl');
    session.start('token-1', 900, admin);
  });

  afterEach(() => http.verify());

  /** Sends a GET and records how it ended. */
  function send(url: string): { error?: HttpErrorResponse; ok?: boolean } {
    const outcome: { error?: HttpErrorResponse; ok?: boolean } = {};
    client.get(url).subscribe({
      next: () => (outcome.ok = true),
      error: (e: HttpErrorResponse) => (outcome.error = e),
    });
    return outcome;
  }

  it('confirms a demotion with /auth/me, then removes admin access without signing out', () => {
    const outcome = send('/api/v1/admin/orders');
    http.expectOne('/api/v1/admin/orders').flush(forbidden.body, forbidden.init);
    const me = http.expectOne(ME);
    expect(me.request.headers.get('Authorization')).toBe('Bearer token-1');
    // The page does not see the error until the role is known.
    expect(outcome.error).toBeUndefined();
    me.flush({ ...admin, role: 'CUSTOMER' });

    expect(outcome.error?.status).toBe(403);
    expect(outcome.error?.error.code).toBe('FORBIDDEN');
    expect(session.isAdmin()).toBe(false);
    expect(access.removed()).toBe(true);
    // Still signed in as the same customer, with the same token; nobody was sent anywhere.
    expect(session.isAuthenticated()).toBe(true);
    expect(session.accessToken()).toBe('token-1');
    expect(session.user()?.id).toBe('u1');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('concludes nothing when /auth/me still says ADMIN (a 403 for another reason)', () => {
    const outcome = send('/api/v1/admin/refunds');
    http.expectOne('/api/v1/admin/refunds').flush(forbidden.body, forbidden.init);
    http.expectOne(ME).flush(admin);

    expect(outcome.error?.status).toBe(403);
    expect(session.isAdmin()).toBe(true);
    expect(access.removed()).toBe(false);
  });

  it('concludes nothing when /auth/me cannot be reached or fails', () => {
    const lost = send('/api/v1/admin/orders');
    http.expectOne('/api/v1/admin/orders').flush(forbidden.body, forbidden.init);
    http.expectOne(ME).error(new ProgressEvent('error'));
    expect(lost.error?.status).toBe(403);

    const broken = send('/api/v1/admin/orders');
    http.expectOne('/api/v1/admin/orders').flush(forbidden.body, forbidden.init);
    http
      .expectOne(ME)
      .flush(
        { title: 'Internal Server Error', status: 500 },
        { status: 500, statusText: 'Server Error' },
      );
    expect(broken.error?.status).toBe(403);

    expect(session.isAdmin()).toBe(true);
    expect(session.isAuthenticated()).toBe(true);
    expect(access.removed()).toBe(false);
  });

  it('leaves unrelated errors alone: other URLs, other statuses, and non-admin sessions', () => {
    send('/api/v1/orders/o1');
    http.expectOne('/api/v1/orders/o1').flush(forbidden.body, forbidden.init);
    send('/api/v1/admin/orders');
    http
      .expectOne('/api/v1/admin/orders')
      .flush({ title: 'Conflict', status: 409 }, { status: 409, statusText: 'Conflict' });
    send('/api/v1/admin/orders');
    http
      .expectOne('/api/v1/admin/orders')
      .flush({ title: 'Server Error', status: 500 }, { status: 500, statusText: 'Server Error' });
    http.expectNone(ME);

    session.start('token-2', 900, { ...admin, role: 'CUSTOMER' });
    const customer = send('/api/v1/admin/orders');
    http.expectOne('/api/v1/admin/orders').flush(forbidden.body, forbidden.init);
    http.expectNone(ME);
    expect(customer.error?.status).toBe(403);
    expect(access.removed()).toBe(false);
  });

  it('checks once for several refused requests at the same time, and not again afterwards', () => {
    const a = send('/api/v1/admin/orders');
    const b = send('/api/v1/admin/dashboard/summary');
    http.expectOne('/api/v1/admin/orders').flush(forbidden.body, forbidden.init);
    http.expectOne('/api/v1/admin/dashboard/summary').flush(forbidden.body, forbidden.init);
    http.expectOne(ME).flush({ ...admin, role: 'CUSTOMER' });
    expect(a.error?.status).toBe(403);
    expect(b.error?.status).toBe(403);

    // The session now knows: a later refusal needs no new check.
    const later = send('/api/v1/admin/refunds');
    http.expectOne('/api/v1/admin/refunds').flush(forbidden.body, forbidden.init);
    http.expectNone(ME);
    expect(later.error?.status).toBe(403);
  });

  it('keeps the state in this tab only and clears it when the user is an ADMIN again', () => {
    send('/api/v1/admin/orders');
    http.expectOne('/api/v1/admin/orders').flush(forbidden.body, forbidden.init);
    http.expectOne(ME).flush({ ...admin, role: 'CUSTOMER' });
    expect(access.removed()).toBe(true);
    // Nothing is shared with other tabs: each finds out on its own next admin request.
    expect(localStorage.length).toBe(0);

    // Promoted again and signed in: the removal no longer applies.
    session.start('token-3', 900, admin);
    expect(access.removed()).toBe(false);
    // A different user in this tab is not affected by the earlier user's removal.
    session.start('token-4', 900, { ...admin, id: 'u2', role: 'CUSTOMER' });
    expect(access.removed()).toBe(false);
  });
});
