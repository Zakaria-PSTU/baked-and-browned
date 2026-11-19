import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';

/** How a test payment ends on the fake provider's page. */
export type FakeOutcome = 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

/** A fake gateway session as its hosted page shows it. */
export interface FakeSession {
  readonly sessionId: string;
  readonly orderNumber: string;
  readonly amount: number;
  readonly currency: string;
  readonly status: 'PENDING' | FakeOutcome;
  /** Where the provider sends the browser afterwards (the API's inert return endpoint). */
  readonly returnUrl: string;
}

/**
 * The fake gateway's dev-only endpoints (dev and test profiles only). They are deliberately not
 * in the API contract, so there is no generated client for them.
 */
@Injectable({ providedIn: 'root' })
export class FakeGatewayApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  session(sessionId: string): Observable<FakeSession> {
    return this.http.get<FakeSession>(this.url(sessionId));
  }

  /** `notifyShop: false` loses the provider's callback, leaving the payment to reconciliation. */
  finish(sessionId: string, outcome: FakeOutcome, notifyShop: boolean): Observable<FakeSession> {
    return this.http.post<FakeSession>(`${this.url(sessionId)}/finish`, { outcome, notifyShop });
  }

  private url(sessionId: string): string {
    return `${this.rootUrl}/dev/fake-gateway/sessions/${encodeURIComponent(sessionId)}`;
  }
}
