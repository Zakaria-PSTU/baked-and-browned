import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import {
  adminListReconciliationCases,
  adminResolveReconciliationCase,
} from '../../core/api/generated/functions';
import type {
  ReconciliationCase,
  ReconciliationResolutionRequest,
} from '../../core/api/generated/models';

export type ResolutionAction = ReconciliationResolutionRequest['action'];

/** ADMIN payment reconciliation cases (contract tag Admin, ADR-0008), returning response bodies. */
@Injectable({ providedIn: 'root' })
export class AdminReconciliationApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  /** Open cases, oldest first (the server's work-queue order); at most 500. */
  open(): Observable<ReconciliationCase[]> {
    return adminListReconciliationCases(this.http, this.rootUrl, { status: 'OPEN' }).pipe(
      map((r) => r.body),
    );
  }

  resolve(caseId: string, action: ResolutionAction, note: string): Observable<ReconciliationCase> {
    return adminResolveReconciliationCase(this.http, this.rootUrl, {
      caseId,
      body: { action, note },
    }).pipe(map((r) => r.body));
  }
}
