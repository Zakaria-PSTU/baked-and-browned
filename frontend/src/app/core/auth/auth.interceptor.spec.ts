import {
  HttpClient,
  HttpXsrfTokenExtractor,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthSession } from './auth-session';

const user = { id: 'u1', fullName: 'Nusrat', phone: '+8801712345678', role: 'CUSTOMER' as const };
const authResponse = (token: string) => ({
  accessToken: token,
  tokenType: 'Bearer',
  expiresIn: 900,
  user,
});
const unauthorized = { status: 401, statusText: 'Unauthorized' };
const problem401 = { title: 'Unauthorized', status: 401 };

/** Let the refresh's promise-based lock wrapper settle so its request is issued. */
const tick = () => new Promise((resolve) => setTimeout(resolve));

describe('authInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let session: AuthSession;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'csrf-1' } },
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(AuthSession);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  it('adds the bearer token to API calls only', () => {
    session.start('t1', 900, user);

    client.get('/api/v1/orders').subscribe();
    expect(http.expectOne('/api/v1/orders').request.headers.get('Authorization')).toBe('Bearer t1');

    client.get('https://cdn.example.com/x.json').subscribe();
    const external = http.expectOne('https://cdn.example.com/x.json');
    expect(external.request.headers.has('Authorization')).toBe(false);

    client.post('/api/v1/auth/login', {}).subscribe();
    expect(http.expectOne('/api/v1/auth/login').request.headers.has('Authorization')).toBe(false);
  });

  it('sends anonymous requests unchanged and does not refresh on their 401', async () => {
    const result = firstValueFrom(client.get('/api/v1/orders'));
    const req = http.expectOne('/api/v1/orders');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(problem401, unauthorized);

    await expect(result).rejects.toMatchObject({ status: 401 });
    http.expectNone('/api/v1/auth/refresh');
  });

  it('refreshes once on 401 and retries with the new token', async () => {
    session.start('old', 900, user);
    const result = firstValueFrom(client.get('/api/v1/orders'));

    http.expectOne('/api/v1/orders').flush(problem401, unauthorized);
    await tick();
    http.expectOne('/api/v1/auth/refresh').flush(authResponse('new'));
    await tick();

    const retry = http.expectOne('/api/v1/orders');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    retry.flush([]);
    expect(await result).toEqual([]);
  });

  it('concurrent 401s share a single refresh', async () => {
    session.start('old', 900, user);
    const a = firstValueFrom(client.get('/api/v1/orders'));
    const b = firstValueFrom(client.get('/api/v1/auth/me'));

    http.expectOne('/api/v1/orders').flush(problem401, unauthorized);
    http.expectOne('/api/v1/auth/me').flush(problem401, unauthorized);
    await tick();
    http.expectOne('/api/v1/auth/refresh').flush(authResponse('new'));
    await tick();

    http.expectOne('/api/v1/orders').flush(['a']);
    http.expectOne('/api/v1/auth/me').flush(user);
    expect(await a).toEqual(['a']);
    expect(await b).toEqual(user);
  });

  it('a failed refresh ends the session and sends the user to log in', async () => {
    session.start('old', 900, user);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const result = firstValueFrom(client.get('/api/v1/orders'));

    http.expectOne('/api/v1/orders').flush(problem401, unauthorized);
    await tick();
    http.expectOne('/api/v1/auth/refresh').flush(problem401, unauthorized);

    await expect(result).rejects.toMatchObject({ status: 401 });
    expect(session.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/' } });
  });

  it('a retried request that fails again is not refreshed a second time', async () => {
    session.start('old', 900, user);
    const result = firstValueFrom(client.get('/api/v1/orders'));

    http.expectOne('/api/v1/orders').flush(problem401, unauthorized);
    await tick();
    http.expectOne('/api/v1/auth/refresh').flush(authResponse('new'));
    await tick();
    http.expectOne('/api/v1/orders').flush(problem401, unauthorized);

    await expect(result).rejects.toMatchObject({ status: 401 });
    http.expectNone('/api/v1/auth/refresh');
  });

  it('refreshes an expired token before sending', async () => {
    session.start('expired', 900, user, Date.now() - 900_000);
    const result = firstValueFrom(client.get('/api/v1/orders'));
    await tick();

    http.expectNone('/api/v1/orders');
    http.expectOne('/api/v1/auth/refresh').flush(authResponse('fresh'));
    await tick();
    const req = http.expectOne('/api/v1/orders');
    expect(req.request.headers.get('Authorization')).toBe('Bearer fresh');
    req.flush([]);
    expect(await result).toEqual([]);
  });

  it('forwards non-401 errors untouched', async () => {
    session.start('t1', 900, user);
    const result = firstValueFrom(client.get('/api/v1/orders'));
    http
      .expectOne('/api/v1/orders')
      .flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });

    await expect(result).rejects.toMatchObject({ status: 403 });
    http.expectNone('/api/v1/auth/refresh');
  });
});
