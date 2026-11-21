import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { ApiConfiguration } from '../api/generated/api-configuration';
import { AdminAccess } from './admin-access';
import { AuthSession } from './auth-session';

/**
 * F-2: when an admin API request is refused with 403 while this tab still believes the user is an
 * ADMIN, check the current role before the error reaches the page (see {@link AdminAccess}). The
 * original error is always passed on unchanged, so pages keep their own error handling; only the
 * role check is added. Other statuses, other URLs and non-admin sessions are untouched.
 */
export const adminAccessInterceptor: HttpInterceptorFn = (req, next) => {
  const adminRoot = `${inject(ApiConfiguration).rootUrl}/admin/`;
  if (!req.url.startsWith(adminRoot)) {
    return next(req);
  }
  const session = inject(AuthSession);
  const access = inject(AdminAccess);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 403 && session.isAdmin()) {
        return access.confirmAfterForbidden().pipe(switchMap(() => throwError(() => error)));
      }
      return throwError(() => error);
    }),
  );
};
