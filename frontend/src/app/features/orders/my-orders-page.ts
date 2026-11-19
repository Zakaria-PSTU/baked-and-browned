import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { CheckoutApi } from '../checkout/checkout-api';
import { OrderList } from './order-list';
import { FINISHED } from './order-status';

/** The server lists at most this many orders, newest first (OrderQueryService.MAX_LISTED). */
export const LISTED = 200;

/** The customer's orders: ones still in progress first, then past ones, each newest first. */
@Component({
  selector: 'bb-my-orders-page',
  imports: [RouterLink, OrderList],
  templateUrl: './my-orders-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyOrdersPage {
  private readonly api = inject(CheckoutApi);

  protected readonly orders = rxResource({ stream: () => this.api.myOrders() });

  protected readonly current = computed(() =>
    (this.orders.value() ?? []).filter((o) => !FINISHED.has(o.status)),
  );
  protected readonly past = computed(() =>
    (this.orders.value() ?? []).filter((o) => FINISHED.has(o.status)),
  );

  /** Only the latest are listed; say so when the list may be cut off. */
  protected readonly truncated = computed(() => (this.orders.value() ?? []).length >= LISTED);
  protected readonly listed = LISTED;
}
