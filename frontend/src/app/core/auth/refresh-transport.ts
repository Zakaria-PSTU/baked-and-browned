import { HttpErrorResponse } from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';
import { ApiConfiguration } from '../api/generated/api-configuration';
import { refresh } from '../api/generated/functions';
import type { AuthResponse } from '../api/generated/models';

/**
 * Sends `POST /auth/refresh` with the given CSRF header. Resolves with the response body, or
 * rejects with an {@link HttpErrorResponse} carrying the HTTP status (0 for a network failure),
 * so callers handle it exactly like an `HttpClient` error.
 */
export type RefreshTransport = (csrfToken: string) => Promise<AuthResponse>;

/**
 * How the refresh request is sent. In the app it is {@link keepaliveRefreshTransport}; tests may
 * substitute one backed by `HttpClient` so `HttpTestingController` can answer it.
 */
export const REFRESH_TRANSPORT = new InjectionToken<RefreshTransport>('REFRESH_TRANSPORT', {
  providedIn: 'root',
  factory: () => keepaliveRefreshTransport(inject(ApiConfiguration).rootUrl),
});

/**
 * QA-01 (ADR-0023 rev 4): a native `fetch` with `keepalive`, so the browser completes the request
 * and stores its `Set-Cookie` (the rotated refresh token) even if the page unloads first.
 * `HttpClient`'s XHR backend ignores `keepalive`, hence the single native call. The API is
 * same-origin (`/api/v1`), so the custom CSRF header needs no preflight.
 */
export function keepaliveRefreshTransport(
  rootUrl: string,
  fetchFn: typeof fetch = (input, init) => globalThis.fetch(input, init),
): RefreshTransport {
  const url = rootUrl + refresh.PATH;
  return async (csrfToken) => {
    let response: Response;
    try {
      response = await fetchFn(url, {
        method: 'POST',
        keepalive: true,
        credentials: 'same-origin',
        headers: { Accept: 'application/json', 'X-CSRF-Token': csrfToken },
      });
    } catch (error: unknown) {
      throw new HttpErrorResponse({ status: 0, statusText: 'Unknown Error', url, error });
    }
    const body: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      throw new HttpErrorResponse({ status: response.status, statusText: response.statusText, url, error: body });
    }
    return body as AuthResponse;
  };
}
