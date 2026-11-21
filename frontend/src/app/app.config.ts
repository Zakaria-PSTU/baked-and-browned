import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { adminAccessInterceptor } from './core/auth/admin-access.interceptor';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      // The API's double-submit CSRF pair (cookie XSRF-TOKEN, header X-CSRF-Token). Angular adds
      // the header only to same-origin, state-changing requests.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-CSRF-Token' }),
      // adminAccessInterceptor sees an admin 403 before authInterceptor passes it on (F-2).
      withInterceptors([authInterceptor, adminAccessInterceptor]),
    ),
    // Resume a session from the refresh cookie before the first route is resolved, so guards and
    // the header see the right state (and there is no flash of the signed-out UI).
    provideAppInitializer(() => inject(AuthService).restore()),
  ],
};
