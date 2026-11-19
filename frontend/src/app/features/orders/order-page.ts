import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import type { OrderDetail } from '../../core/api/generated/models';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaRange } from '../../shared/dhaka-time';
import { CheckoutApi } from '../checkout/checkout-api';
import { CancelOrderPanel } from './cancel-order-panel';
import { ManualPaymentPanel } from './manual-payment-panel';
import { OnlinePaymentPanel } from './online-payment-panel';
import { statusLabel } from './order-status';

/**
 * One of the customer's orders, as snapshotted when it was placed. Messages and names are shown
 * as text, never HTML (business rules, Customization).
 */
@Component({
  selector: 'bb-order-page',
  imports: [RouterLink, BdtPipe, ManualPaymentPanel, OnlinePaymentPanel, CancelOrderPanel],
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

  /** Set when the customer cancelled on this page, for the confirmation message. */
  protected readonly justCancelled = signal(false);

  protected onCancelled(order: OrderDetail): void {
    this.order.set(order);
    this.justCancelled.set(true);
  }

  /** The latest refund on the order, e.g. after cancelling a paid order (ADR-0009). */
  protected refundNote(order: OrderDetail): string | null {
    switch (order.payment.refundStatus) {
      case 'REQUESTED':
      case 'APPROVED':
        return 'We are processing a refund for this order and will send you an SMS when it is done.';
      case 'COMPLETED':
        return 'A refund for this order has been sent.';
      case 'REJECTED':
        return 'We could not refund this order. Please contact us for help.';
      default:
        return null;
    }
  }

  protected statusLabel(order: OrderDetail): string {
    if (
      order.status === 'PENDING_PAYMENT' &&
      order.payment.manualSubmissionStatus === 'SUBMITTED'
    ) {
      return 'Checking your payment';
    }
    return statusLabel(order.status);
  }
}
