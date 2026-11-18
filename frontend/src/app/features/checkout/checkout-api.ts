import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import {
  getOrder,
  listDeliverySlots,
  listDeliveryZones,
  placeOrder,
  quoteCheckout,
} from '../../core/api/generated/functions';
import type {
  CheckoutQuote,
  CheckoutQuoteRequest,
  DeliverySlot,
  DeliveryZone,
  OrderDetail,
  PlaceOrderRequest,
} from '../../core/api/generated/models';

/** Checkout and order reads/writes (contract tags Catalog, Checkout, Orders), returning bodies. */
@Injectable({ providedIn: 'root' })
export class CheckoutApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  zones(): Observable<DeliveryZone[]> {
    return listDeliveryZones(this.http, this.rootUrl).pipe(map((r) => r.body));
  }

  /** Bookable slots on an Asia/Dhaka calendar date (`YYYY-MM-DD`). */
  slots(date: string): Observable<DeliverySlot[]> {
    return listDeliverySlots(this.http, this.rootUrl, { date }).pipe(map((r) => r.body));
  }

  quote(body: CheckoutQuoteRequest): Observable<CheckoutQuote> {
    return quoteCheckout(this.http, this.rootUrl, { body }).pipe(map((r) => r.body));
  }

  place(body: PlaceOrderRequest): Observable<OrderDetail> {
    return placeOrder(this.http, this.rootUrl, { body }).pipe(map((r) => r.body));
  }

  order(orderId: string): Observable<OrderDetail> {
    return getOrder(this.http, this.rootUrl, { orderId }).pipe(map((r) => r.body));
  }
}
