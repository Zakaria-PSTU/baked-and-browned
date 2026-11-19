import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { finalize } from 'rxjs';
import type { OrderDetail } from '../../core/api/generated/models';
import { userMessage } from '../../core/http/problem';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { CheckoutApi } from '../checkout/checkout-api';

/** The server's limit on the optional reason (contract `CancelOrderRequest`). */
export const REASON_MAX = 500;

/**
 * Lets the customer cancel their own order while the server says it is `cancellable` (ADR-0009).
 * The deadline and status checks are the server's; this only asks for confirmation first.
 */
@Component({
  selector: 'bb-cancel-order-panel',
  templateUrl: './cancel-order-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CancelOrderPanel {
  private readonly api = inject(CheckoutApi);

  readonly order = input.required<OrderDetail>();

  /** The order as it is after cancelling. */
  readonly cancelled = output<OrderDetail>();

  protected readonly confirming = signal(false);
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly formatDateTime = formatDhakaDateTime;
  protected readonly reasonMax = REASON_MAX;

  protected start(): void {
    this.error.set(null);
    this.confirming.set(true);
  }

  protected keep(): void {
    this.confirming.set(false);
    this.error.set(null);
  }

  protected typeReason(event: Event): void {
    this.reason.set((event.target as HTMLTextAreaElement).value);
  }

  protected confirm(): void {
    if (this.busy()) {
      return;
    }
    const reason = this.reason().trim();
    if (reason.length > REASON_MAX) {
      this.error.set(`Please keep the reason under ${REASON_MAX} characters.`);
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .cancel(this.order().orderId, reason || undefined)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (order) => this.cancelled.emit(order),
        error: (err: unknown) =>
          this.error.set(userMessage(err, 'We could not cancel your order. Please try again.')),
      });
  }
}
