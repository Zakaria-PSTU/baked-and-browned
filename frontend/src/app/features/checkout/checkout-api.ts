import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import {
  getOrder,
  listDeliverySlots,
  listDeliveryZones,
  listManualPaymentAccounts,
  listMyOrders,
  placeOrder,
  quoteCheckout,
  submitManualPayment,
} from '../../core/api/generated/functions';
import type {
  CheckoutQuote,
  CheckoutQuoteRequest,
  DeliverySlot,
  DeliveryZone,
  ManualPaymentAccount,
  ManualPaymentProvider,
  ManualPaymentSubmission,
  Order,
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

  /** The customer's own orders, newest first (the server lists the latest 200). */
  myOrders(): Observable<Order[]> {
    return listMyOrders(this.http, this.rootUrl).pipe(map((r) => r.body));
  }

  order(orderId: string): Observable<OrderDetail> {
    return getOrder(this.http, this.rootUrl, { orderId }).pipe(map((r) => r.body));
  }

  /** Where to send a MANUAL payment; empty when it is not offered (ADR-0022). */
  manualAccounts(): Observable<ManualPaymentAccount[]> {
    return listManualPaymentAccounts(this.http, this.rootUrl).pipe(map((r) => r.body));
  }

  /** Records the customer's transfer reference; it never confirms the payment (ADR-0011). */
  submitManual(
    orderId: string,
    provider: ManualPaymentProvider,
    transactionReference: string,
    proof?: File,
  ): Observable<ManualPaymentSubmission> {
    return submitManualPayment(this.http, this.rootUrl, {
      orderId,
      body: { provider, transactionReference, proof },
    }).pipe(map((r) => r.body));
  }
}
