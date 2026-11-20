import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import {
  adminCompleteRefund,
  adminDecideRefund,
  adminListRefunds,
  adminRequestRefund,
} from '../../core/api/generated/functions';
import type { Refund, RefundStatus } from '../../core/api/generated/models';

/** ADMIN refunds (contract tag Admin; ADR-0011, ADR-0017), returning response bodies. */
@Injectable({ providedIn: 'root' })
export class AdminRefundsApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  /** Refunds newest first by the server (at most 500), optionally only one status. */
  list(status?: RefundStatus): Observable<Refund[]> {
    return adminListRefunds(this.http, this.rootUrl, status ? { status } : undefined).pipe(
      map((r) => r.body),
    );
  }

  /** Opens a request for another ADMIN to approve; without `amount`, everything not yet refunded. */
  request(paymentId: string, reason: string, amount?: number): Observable<Refund> {
    return adminRequestRefund(this.http, this.rootUrl, {
      paymentId,
      body: amount === undefined ? { reason } : { reason, amount },
    }).pipe(map((r) => r.body));
  }

  decide(refundId: string, decision: 'APPROVE' | 'REJECT', note: string): Observable<Refund> {
    return adminDecideRefund(this.http, this.rootUrl, {
      refundId,
      body: { decision, note },
    }).pipe(map((r) => r.body));
  }

  /** Records the reference of money already sent; the customer is told by SMS. */
  complete(refundId: string, externalReference: string): Observable<Refund> {
    return adminCompleteRefund(this.http, this.rootUrl, {
      refundId,
      body: { externalReference },
    }).pipe(map((r) => r.body));
  }
}
