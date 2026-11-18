import { HttpXsrfTokenExtractor, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { AuthSession } from './auth-session';
import { withCrossTabLock } from './cross-tab-lock';

const user = { id: 'u1', fullName: 'Nusrat', phone: '+8801712345678', role: 'CUSTOMER' as const };
const authResponse = (token: string) => ({
  accessToken: token,
  tokenType: 'Bearer',
  expiresIn: 900,
  user,
});

/** Let the promise-based lock wrapper settle so the refresh request is issued. */
const tick = () => new Promise((resolve) => setTimeout(resolve));

describe('AuthService', () => {
  let auth: AuthService;
  let session: AuthSession;
  let http: HttpTestingController;
  let csrfCookie: string | null;

  beforeEach(() => {
    csrfCookie = 'csrf-1';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => csrfCookie } },
      ],
    });
    auth = TestBed.inject(AuthService);
    session = TestBed.inject(AuthSession);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('logs in and keeps the session in memory', async () => {
    const done = firstValueFrom(auth.login('+8801712345678', 'correct horse battery'));
    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      identifier: '+8801712345678',
      password: 'correct horse battery',
    });
    req.flush(authResponse('t1'));
    await done;

    expect(session.accessToken()).toBe('t1');
    expect(session.user()?.fullName).toBe('Nusrat');
  });

  it('registers, then logs in with the same credentials', async () => {
    const done = firstValueFrom(
      auth.register({ fullName: 'Nusrat', phone: '+8801712345678', password: 'p'.repeat(12) }),
    );
    http.expectOne('/api/v1/auth/register').flush(user, { status: 201, statusText: 'Created' });
    const login = http.expectOne('/api/v1/auth/login');
    expect(login.request.body).toEqual({ identifier: '+8801712345678', password: 'p'.repeat(12) });
    login.flush(authResponse('t1'));
    await done;
    expect(session.isAuthenticated()).toBe(true);
  });

  it('shares one refresh request between concurrent callers and sends the CSRF header', async () => {
    const first = firstValueFrom(auth.refresh());
    const second = firstValueFrom(auth.refresh());
    await tick();

    const req = http.expectOne('/api/v1/auth/refresh');
    expect(req.request.headers.get('X-CSRF-Token')).toBe('csrf-1');
    req.flush(authResponse('t2'));

    expect(await first).toBe('t2');
    expect(await second).toBe('t2');
    expect(session.accessToken()).toBe('t2');

    // Once settled, the next refresh is a new request.
    const third = firstValueFrom(auth.refresh());
    await tick();
    http.expectOne('/api/v1/auth/refresh').flush(authResponse('t3'));
    expect(await third).toBe('t3');
  });

  it('clears the session when the refresh is rejected', async () => {
    session.start('stale', 900, user);
    const result = firstValueFrom(auth.refresh());
    await tick();
    http
      .expectOne('/api/v1/auth/refresh')
      .flush(
        { title: 'Unauthorized', status: 401, code: 'INVALID_REFRESH_TOKEN' },
        { status: 401, statusText: 'Unauthorized' },
      );

    await expect(result).rejects.toBeTruthy();
    expect(session.isAuthenticated()).toBe(false);
  });

  it('restores a session only when a CSRF cookie suggests there is one', async () => {
    csrfCookie = null;
    await auth.restore();
    http.expectNone('/api/v1/auth/refresh');

    csrfCookie = 'csrf-1';
    const restored = auth.restore();
    await tick();
    http.expectOne('/api/v1/auth/refresh').flush(authResponse('t1'));
    await restored;
    expect(session.accessToken()).toBe('t1');
  });

  it('restore never fails: an expired cookie means anonymous', async () => {
    const restored = auth.restore();
    await tick();
    http
      .expectOne('/api/v1/auth/refresh')
      .flush({ title: 'Unauthorized', status: 401 }, { status: 401, statusText: 'Unauthorized' });
    await expect(restored).resolves.toBeUndefined();
    expect(session.isAuthenticated()).toBe(false);
  });

  it('clears the local session on logout even if the server call fails', async () => {
    session.start('t1', 900, user);
    const done = firstValueFrom(auth.logout(), { defaultValue: undefined });
    const req = http.expectOne('/api/v1/auth/logout');
    expect(req.request.headers.get('X-CSRF-Token')).toBe('csrf-1');
    req.flush('', { status: 500, statusText: 'Server Error' });
    await done;
    expect(session.isAuthenticated()).toBe(false);
  });
});

describe('withCrossTabLock', () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'locks');
  });

  it('runs the work under the named Web Lock when available', async () => {
    const order: string[] = [];
    let tail: Promise<unknown> = Promise.resolve();
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        // Minimal exclusive lock: each request waits for the previous one to finish.
        request: (name: string, callback: () => Promise<unknown>) => {
          order.push(`acquire ${name}`);
          const run = tail.then(callback);
          tail = run.catch(() => undefined);
          return run;
        },
      },
    });

    const slow = withCrossTabLock('bb-auth-refresh', async () => {
      await new Promise((resolve) => setTimeout(resolve, 10));
      order.push('first done');
      return 1;
    });
    const fast = withCrossTabLock('bb-auth-refresh', async () => {
      order.push('second ran');
      return 2;
    });

    expect(await Promise.all([slow, fast])).toEqual([1, 2]);
    expect(order).toEqual([
      'acquire bb-auth-refresh',
      'acquire bb-auth-refresh',
      'first done',
      'second ran',
    ]);
  });

  it('falls back to running directly without Web Locks', async () => {
    expect(await withCrossTabLock('x', async () => 'ok')).toBe('ok');
  });
});
