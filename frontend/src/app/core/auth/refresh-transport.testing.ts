import { HttpClient } from '@angular/common/http';
import { Provider, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiConfiguration } from '../api/generated/api-configuration';
import { refresh } from '../api/generated/functions';
import { REFRESH_TRANSPORT, RefreshTransport } from './refresh-transport';

/**
 * Tests only: sends the refresh through `HttpClient`, so `HttpTestingController` can answer it
 * (`expectOne('/api/v1/auth/refresh')`). The app uses the keepalive `fetch` transport.
 */
export function provideHttpClientRefreshTransport(): Provider {
  return {
    provide: REFRESH_TRANSPORT,
    useFactory: (): RefreshTransport => {
      const http = inject(HttpClient);
      const rootUrl = inject(ApiConfiguration).rootUrl;
      return async (csrfToken) =>
        (await firstValueFrom(refresh(http, rootUrl, { 'X-CSRF-Token': csrfToken }))).body;
    },
  };
}
