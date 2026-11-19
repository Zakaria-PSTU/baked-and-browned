import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import { adminListPayments, adminVerifyPayment } from '../../core/api/generated/functions';
import type { AdminPayment, AdminPaymentPage } from '../../core/api/generated/models';

/** Proof files the server accepts and returns (ADR-0011). */
export const PROOF_ACCEPT = 'image/jpeg, image/png, application/pdf';

/** ADMIN payment verification (contract tag Admin), returning response bodies. */
@Injectable({ providedIn: 'root' })
export class AdminPaymentsApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  /** MANUAL payments with a submission waiting for an ADMIN decision, oldest first by the server. */
  awaitingVerification(page: number, size: number): Observable<AdminPaymentPage> {
    return adminListPayments(this.http, this.rootUrl, {
      awaitingVerification: true,
      page,
      size,
    }).pipe(map((r) => r.body));
  }

  verify(
    paymentId: string,
    decision: 'APPROVE' | 'REJECT',
    note: string,
  ): Observable<AdminPayment> {
    return adminVerifyPayment(this.http, this.rootUrl, {
      paymentId,
      body: { decision, note },
    }).pipe(map((r) => r.body));
  }

  /**
   * The latest submission's proof file. Requested directly rather than through the generated
   * functions, which are split per type and each send a single `Accept` type: the page cannot know
   * the file's type in advance, and the server returns it in its own type whatever is asked, so
   * this one request accepts all three.
   */
  proof(paymentId: string): Observable<Blob> {
    return this.http.get(`${this.rootUrl}/admin/payments/${encodeURIComponent(paymentId)}/proof`, {
      responseType: 'blob',
      headers: { Accept: PROOF_ACCEPT },
    });
  }
}
