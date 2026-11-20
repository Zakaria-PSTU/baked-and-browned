import { HttpClient, HttpErrorResponse, HttpXsrfTokenExtractor } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  Observable,
  catchError,
  defer,
  finalize,
  firstValueFrom,
  map,
  of,
  shareReplay,
  switchMap,
  throwError,
} from 'rxjs';
import { ApiConfiguration } from '../api/generated/api-configuration';
import { getCurrentUser, login, logout, register } from '../api/generated/functions';
import type { AuthResponse, RegisterRequest } from '../api/generated/models';
import { AuthSession, SessionUser } from './auth-session';
import { withCrossTabLock } from './cross-tab-lock';
import { RefreshRecovery } from './refresh-recovery';
import { REFRESH_TRANSPORT } from './refresh-transport';

/** Web Lock name serializing refreshes across tabs (see {@link withCrossTabLock}). */
export const REFRESH_LOCK = 'bb-auth-refresh';

/**
 * Login, registration, logout and access-token refresh against `/api/v1/auth`.
 *
 * Refresh tokens rotate on every use and a reused one ends the session server-side, so
 * {@link refresh} is single-flight within the tab (concurrent callers share one request) and
 * serialized across tabs with a Web Lock.
 *
 * QA-01 (ADR-0023 rev 4): the refresh is sent with `keepalive` ({@link REFRESH_TRANSPORT}), so a
 * navigation, reload or tab close no longer drops the rotated cookie, and the next page waits
 * briefly for that cookie ({@link RefreshRecovery}) before it refreshes.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;
  private readonly session = inject(AuthSession);
  private readonly xsrf = inject(HttpXsrfTokenExtractor);
  private readonly transport = inject(REFRESH_TRANSPORT);
  private readonly recovery = inject(RefreshRecovery);

  private inFlightRefresh: Observable<string> | null = null;

  login(identifier: string, password: string): Observable<void> {
    return login(this.http, this.rootUrl, { body: { identifier, password } }).pipe(
      map((response) => this.start(response.body)),
    );
  }

  /** Registers a customer, then logs in with the same credentials. */
  register(request: RegisterRequest): Observable<void> {
    return register(this.http, this.rootUrl, { body: request }).pipe(
      switchMap(() => this.login(request.phone, request.password)),
    );
  }

  /**
   * Rotates the refresh cookie and returns the new access token. A 401/403 means the session is
   * over (expired, revoked or reused), so the local session is cleared too.
   */
  refresh(): Observable<string> {
    this.inFlightRefresh ??= defer(() =>
      withCrossTabLock(REFRESH_LOCK, async () => {
        // A refresh interrupted by an earlier page may still be storing its rotated cookies.
        await this.recovery.awaitInterruptedRefresh(() => this.xsrf.getToken());
        // The CSRF cookie is read inside the lock: another tab may just have rotated it.
        const csrfToken = this.csrfToken();
        this.recovery.markInFlight(this.xsrf.getToken());
        try {
          const response = await this.transport(csrfToken);
          this.recovery.clearInFlight();
          return response;
        } catch (error: unknown) {
          // Status 0: no response reached this page. An unload aborts the page's promise while the
          // keepalive request still completes, so the marker stays for the next page to wait on.
          if (!(error instanceof HttpErrorResponse && error.status === 0)) {
            this.recovery.clearInFlight();
          }
          throw error;
        }
      }),
    ).pipe(
      map((response) => {
        this.start(response);
        return response.accessToken;
      }),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
          this.session.clear();
        }
        return throwError(() => error);
      }),
      finalize(() => (this.inFlightRefresh = null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.inFlightRefresh;
  }

  /**
   * App start: resume the session from the refresh cookie, if there might be one. The readable
   * XSRF-TOKEN cookie is issued alongside the HttpOnly refresh cookie, so without it there is no
   * session to resume and no request is made. Never fails: anonymous is a valid outcome.
   */
  async restore(): Promise<void> {
    if (!this.xsrf.getToken()) {
      return;
    }
    try {
      await firstValueFrom(this.refresh());
    } catch {
      // Expired or revoked: continue anonymously.
    }
  }

  /** Revokes the server session; the local session is cleared even if the request fails. */
  logout(): Observable<void> {
    return logout(this.http, this.rootUrl, { 'X-CSRF-Token': this.csrfToken() }).pipe(
      map(() => undefined),
      catchError(() => of(undefined)),
      map(() => this.session.clear()),
    );
  }

  /** Reloads the signed-in user's details from `GET /auth/me`. */
  reloadCurrentUser(): Observable<SessionUser> {
    return getCurrentUser(this.http, this.rootUrl).pipe(
      map((response) => {
        this.session.updateUser(response.body);
        return response.body;
      }),
    );
  }

  private start(response: AuthResponse): void {
    this.session.start(response.accessToken, response.expiresIn, response.user);
  }

  private csrfToken(): string {
    return this.xsrf.getToken() ?? '';
  }
}
