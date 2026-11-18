import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import { adminDashboardSummary } from '../../core/api/generated/functions';
import type { StrictHttpResponse } from '../../core/api/generated/strict-http-response';

/** The contract's inline `adminDashboardSummary` 200 body, taken from the generated client. */
export type DashboardSummary =
  ReturnType<typeof adminDashboardSummary> extends Observable<StrictHttpResponse<infer T>>
    ? T
    : never;

/** ADMIN dashboard reads (contract tag Admin), returning response bodies. */
@Injectable({ providedIn: 'root' })
export class AdminDashboardApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  summary(): Observable<DashboardSummary> {
    return adminDashboardSummary(this.http, this.rootUrl).pipe(map((r) => r.body));
  }
}
