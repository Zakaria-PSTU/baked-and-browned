import { Injectable } from '@angular/core';

/** `localStorage` key of the in-flight refresh marker. */
export const IN_FLIGHT_KEY = 'bb.auth.refresh-in-flight.v1';

/** How long after a refresh started a later page waits for its rotated cookies (ADR-0023 rev 4). */
export const RECOVERY_BUDGET_MS = 5000;

/** How often the readable `XSRF-TOKEN` cookie is checked while waiting. */
export const RECOVERY_POLL_MS = 100;

interface InFlightMarker {
  /** When the refresh was sent (epoch ms). */
  ts: number;
  /** The readable XSRF-TOKEN value then; not a secret. Every successful refresh replaces it. */
  xsrf: string | null;
}

/**
 * QA-01 recovery across page loads. A refresh writes a marker before it is sent and removes it
 * when its response arrives. If the page unloads first, the `keepalive` request still completes
 * and the browser stores the rotated cookies a little later; the next page finds the marker and
 * waits for `XSRF-TOKEN` to change before refreshing, instead of presenting the spent token.
 *
 * Callers hold the cross-tab refresh lock, so markers never interleave. Storage failures (blocked
 * or full storage) only disable the recovery; they never fail a refresh.
 */
@Injectable({ providedIn: 'root' })
export class RefreshRecovery {
  /** Records that a refresh is about to be sent while `xsrf` is the current CSRF cookie. */
  markInFlight(xsrf: string | null, now = Date.now()): void {
    const marker: InFlightMarker = { ts: now, xsrf };
    this.write(JSON.stringify(marker));
  }

  /** The refresh's response (or error) arrived in this page. */
  clearInFlight(): void {
    this.write(null);
  }

  /**
   * If an earlier refresh was interrupted less than {@link RECOVERY_BUDGET_MS} ago, waits until the
   * CSRF cookie differs from the one it was sent with (its rotation was stored) or the budget runs
   * out, then removes the marker. Resolves without waiting when there is nothing to recover.
   *
   * The marker stays in place while waiting: if this page is itself unloaded mid-wait (rapid
   * reloads), the next page still finds it and keeps waiting for the same rotation.
   */
  async awaitInterruptedRefresh(readXsrf: () => string | null): Promise<void> {
    const marker = this.read();
    if (!marker) {
      return;
    }
    const now = Date.now();
    // A marker from the future (clock change) still waits at most one budget.
    const deadline = Math.min(marker.ts, now) + RECOVERY_BUDGET_MS;
    while (Date.now() < deadline && readXsrf() === marker.xsrf) {
      await new Promise((resolve) => setTimeout(resolve, Math.min(RECOVERY_POLL_MS, deadline - Date.now())));
    }
    this.write(null);
  }

  private read(): InFlightMarker | null {
    try {
      const raw = globalThis.localStorage?.getItem(IN_FLIGHT_KEY);
      if (!raw) {
        return null;
      }
      const parsed: unknown = JSON.parse(raw);
      if (
        typeof parsed === 'object' &&
        parsed !== null &&
        typeof (parsed as InFlightMarker).ts === 'number' &&
        Number.isFinite((parsed as InFlightMarker).ts) &&
        (typeof (parsed as InFlightMarker).xsrf === 'string' || (parsed as InFlightMarker).xsrf === null)
      ) {
        return parsed as InFlightMarker;
      }
    } catch {
      // Unreadable storage or a malformed marker: nothing to recover.
    }
    this.write(null);
    return null;
  }

  private write(value: string | null): void {
    try {
      if (value === null) {
        globalThis.localStorage?.removeItem(IN_FLIGHT_KEY);
      } else {
        globalThis.localStorage?.setItem(IN_FLIGHT_KEY, value);
      }
    } catch {
      // Storage blocked or full: the refresh still works, only the recovery is lost.
    }
  }
}
