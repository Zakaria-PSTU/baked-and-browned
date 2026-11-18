import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import {
  adminChangeOrderStatus,
  adminGetOrder,
  adminListOrders,
} from '../../core/api/generated/functions';
import type { AdminOrderDetail, Order, OrderStatus } from '../../core/api/generated/models';

/** The server lists at most this many orders, newest first (OrderQueryService.MAX_ADMIN_LISTED). */
export const ADMIN_LISTED = 500;

/** ADMIN order operations (contract tag Admin), returning response bodies. */
@Injectable({ providedIn: 'root' })
export class AdminOrdersApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  list(status?: OrderStatus): Observable<Order[]> {
    return adminListOrders(this.http, this.rootUrl, { status }).pipe(map((r) => r.body));
  }

  get(orderId: string): Observable<AdminOrderDetail> {
    return adminGetOrder(this.http, this.rootUrl, { orderId }).pipe(map((r) => r.body));
  }

  /** A fulfillment step or an ADMIN cancellation (`reason` required then). */
  changeStatus(orderId: string, status: OrderStatus, reason?: string): Observable<Order> {
    return adminChangeOrderStatus(this.http, this.rootUrl, {
      orderId,
      body: reason ? { status, reason } : { status },
    }).pipe(map((r) => r.body));
  }
}
