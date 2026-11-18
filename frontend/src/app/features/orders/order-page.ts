import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import type { OrderStatus } from '../../core/api/generated/models';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaRange } from '../../shared/dhaka-time';
import { CheckoutApi } from '../checkout/checkout-api';

const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Waiting for payment',
  CONFIRMED: 'Confirmed',
  PREPARING: 'Being baked',
  READY: 'Ready',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

/**
 * One of the customer's orders, as snapshotted when it was placed. Messages and names are shown
 * as text, never HTML (business rules, Customization).
 */
@Component({
  selector: 'bb-order-page',
  imports: [RouterLink, BdtPipe],
  templateUrl: './order-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPage {
  private readonly api = inject(CheckoutApi);

  /** Route parameter `:orderId`. */
  readonly orderId = input.required<string>();

  /** `?placed=1` right after checkout. */
  readonly placed = input<string | undefined>();

  protected readonly order = rxResource({
    params: () => this.orderId(),
    stream: ({ params }) => this.api.order(params),
  });

  protected readonly notFound = computed(() => {
    const error = this.order.error();
    const cause = error && 'cause' in error ? error.cause : error;
    return cause instanceof HttpErrorResponse && cause.status === 404;
  });

  protected readonly formatRange = formatDhakaRange;

  protected statusLabel(status: OrderStatus): string {
    return STATUS_LABELS[status] ?? status;
  }
}
