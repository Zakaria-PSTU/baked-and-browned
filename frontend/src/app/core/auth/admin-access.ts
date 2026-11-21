import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay } from 'rxjs';
import { AuthService } from './auth.service';
import { AuthSession } from './auth-session';

/**
 * QA finding F-2: an ADMIN demoted while signed in keeps the ADMIN role in this page's session
 * until the next refresh, but the API refuses their admin requests at once (403, ADR-0028).
 *
 * After an admin request is refused, {@link confirmAfterForbidden} asks `GET /auth/me` for the
 * account's current role (it rotates nothing and signs no one out). Only when the answer is not
 * ADMIN is the loss confirmed: the session's role is updated (hiding admin navigation, and the
 * route guard closes admin pages) and {@link removed} turns on, which the shell shows as an inline
 * "Admin access removed" message over a read-only page. A failed check concludes nothing.
 *
 * Per tab: each tab finds out on its own next admin request.
 */
@Injectable({ providedIn: 'root' })
export class AdminAccess {
  private readonly auth = inject(AuthService);
  private readonly session = inject(AuthSession);

  /** The user whose admin access was confirmed removed in this tab, if any. */
  private readonly removedFor = signal<string | null>(null);
  private inFlight: Observable<void> | null = null;

  /** True while the signed-in user is the one confirmed demoted and is still not an ADMIN. */
  readonly removed = computed(() => {
    const user = this.session.user();
    return !!user && user.id === this.removedFor() && user.role !== 'ADMIN';
  });

  /**
   * Checks the current role after a refused admin request. Concurrent calls share one request.
   * Never fails: a failed check leaves everything as it was.
   */
  confirmAfterForbidden(): Observable<void> {
    this.inFlight ??= this.auth.reloadCurrentUser().pipe(
      map((user) => {
        if (user.role !== 'ADMIN') {
          this.removedFor.set(user.id);
        }
      }),
      catchError(() => of(undefined)),
      finalize(() => (this.inFlight = null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.inFlight;
  }
}
