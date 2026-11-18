import { TestBed } from '@angular/core/testing';
import {
  CanMatchFn,
  PartialMatchRouteSnapshot,
  Route,
  Router,
  UrlSegment,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { requireAdmin, requireSignedIn, requireSignedOut } from './auth.guards';
import { AuthSession } from './auth-session';

const user = { id: 'u1', fullName: 'Nusrat', phone: '+8801712345678', role: 'CUSTOMER' as const };

function run(guard: CanMatchFn, path: string): unknown {
  const segments = path
    .split('/')
    .filter(Boolean)
    .map((p) => new UrlSegment(p, {}));
  return TestBed.runInInjectionContext(() =>
    guard({} as Route, segments, {} as PartialMatchRouteSnapshot),
  );
}

describe('auth guards', () => {
  let session: AuthSession;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    session = TestBed.inject(AuthSession);
    router = TestBed.inject(Router);
  });

  const url = (result: unknown) =>
    result instanceof UrlTree ? router.serializeUrl(result) : result;

  it('sends anonymous users to login with a return URL', () => {
    expect(url(run(requireSignedIn, '/account'))).toBe('/login?returnUrl=%2Faccount');
    session.start('t', 900, user);
    expect(run(requireSignedIn, '/account')).toBe(true);
  });

  it('lets only admins into admin routes', () => {
    expect(url(run(requireAdmin, '/admin'))).toBe('/login?returnUrl=%2Fadmin');
    session.start('t', 900, user);
    expect(url(run(requireAdmin, '/admin'))).toBe('/');
    session.start('t', 900, { ...user, role: 'ADMIN' });
    expect(run(requireAdmin, '/admin')).toBe(true);
  });

  it('keeps signed-in users away from login and registration', () => {
    expect(run(requireSignedOut, '/login')).toBe(true);
    session.start('t', 900, user);
    expect(url(run(requireSignedOut, '/login'))).toBe('/account');
  });
});
