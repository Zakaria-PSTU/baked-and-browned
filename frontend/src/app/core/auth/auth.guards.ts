import { inject } from '@angular/core';
import { CanMatchFn, Router, UrlSegment, UrlTree } from '@angular/router';
import { AuthSession } from './auth-session';

/**
 * Route guards are a UX convenience only: the API enforces authentication and roles on every
 * request. The session has already been restored by the app initializer when these run.
 */

function loginRedirect(router: Router, segments: UrlSegment[]): UrlTree {
  const returnUrl = '/' + segments.map((s) => s.path).join('/');
  return router.createUrlTree(['/login'], { queryParams: { returnUrl } });
}

/** Signed-in users only; others go to /login and come back afterwards. */
export const requireSignedIn: CanMatchFn = (_route, segments) =>
  inject(AuthSession).isAuthenticated() || loginRedirect(inject(Router), segments);

/** ADMIN only; signed-in customers are sent home, anonymous users to /login. */
export const requireAdmin: CanMatchFn = (_route, segments) => {
  const session = inject(AuthSession);
  const router = inject(Router);
  if (!session.isAuthenticated()) {
    return loginRedirect(router, segments);
  }
  return session.isAdmin() || router.createUrlTree(['/']);
};

/** Login and registration pages are pointless once signed in. */
export const requireSignedOut: CanMatchFn = () =>
  !inject(AuthSession).isAuthenticated() || inject(Router).createUrlTree(['/account']);
