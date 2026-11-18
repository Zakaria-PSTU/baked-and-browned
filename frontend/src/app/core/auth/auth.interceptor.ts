import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, of, switchMap, throwError } from 'rxjs';
import { ApiConfiguration } from '../api/generated/api-configuration';
import { AuthService } from './auth.service';
import { AuthSession } from './auth-session';

/** Public auth operations: never send a bearer token, never refresh-and-retry them. */
const PUBLIC_AUTH = /\/auth\/(login|register|refresh|logout)$/;

/**
 * Adds `Authorization: Bearer` to our own API calls (never to other origins) and recovers from
 * an expired access token:
 * - a token known to be expired is refreshed before the request is sent;
 * - a 401 for a request that carried a token triggers one refresh (shared by all concurrent
 *   requests, see {@link AuthService.refresh}) and one retry;
 * - if the refresh fails, the session is over: the user is sent to /login and comes back after.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const rootUrl = inject(ApiConfiguration).rootUrl;
  if (!req.url.startsWith(rootUrl + '/') || PUBLIC_AUTH.test(req.url)) {
    return next(req);
  }
  const session = inject(AuthSession);
  const auth = inject(AuthService);
  const router = inject(Router);

  const withToken = (token: string | null): HttpRequest<unknown> =>
    token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  const initialToken = session.needsRefresh()
    ? auth.refresh().pipe(catchError(() => of(null)))
    : of(session.accessToken());

  return initialToken.pipe(
    switchMap((token) =>
      next(withToken(token)).pipe(
        catchError((error: unknown) => {
          if (!(error instanceof HttpErrorResponse) || error.status !== 401 || token === null) {
            return throwError(() => error);
          }
          // Another request may already have refreshed while this one was in flight.
          const current = session.accessToken();
          const fresh = current && current !== token ? of(current) : auth.refresh();
          return fresh.pipe(
            catchError(() => {
              session.clear();
              void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
              return throwError(() => error);
            }),
            switchMap((newToken) => next(withToken(newToken))),
          );
        }),
      ),
    ),
  );
};
