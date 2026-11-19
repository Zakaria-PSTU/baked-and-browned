import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  InjectionToken,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { defer, repeat, takeWhile } from 'rxjs';
import type { OrderDetail } from '../../core/api/generated/models';
import { CheckoutApi } from '../checkout/checkout-api';

/** How the return page waits for the provider's verified callback. */
export interface PaymentPolling {
  readonly intervalMs: number;
  readonly attempts: number;
}

/** About a minute by default; tests shorten it. */
export const PAYMENT_POLLING = new InjectionToken<PaymentPolling>('PAYMENT_POLLING', {
  factory: () => ({ intervalMs: 3000, attempts: 20 }),
});

/** Still waiting on the provider: the order is unpaid and the attempt is neither failed nor cancelled. */
export function awaitingPayment(order: OrderDetail): boolean {
  return order.status === 'PENDING_PAYMENT' && order.payment.status === 'PENDING';
}

/**
 * Where the browser lands after the hosted payment page (`/orders/:orderId/payment`, via the API's
 * inert return endpoint). Nothing in the redirect is trusted: the page re-reads the order until the
 * server has an outcome from the verified callback, then shows the order page. If none arrives in
 * time it says so; the reconciliation worker settles the payment later.
 */
@Component({
  selector: 'bb-payment-return-page',
  imports: [RouterLink],
  templateUrl: './payment-return-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentReturnPage implements OnInit {
  private readonly api = inject(CheckoutApi);
  private readonly router = inject(Router);
  private readonly polling = inject(PAYMENT_POLLING);
  private readonly destroyRef = inject(DestroyRef);

  /** Route parameter `:orderId`. */
  readonly orderId = input.required<string>();

  protected readonly state = signal<'checking' | 'slow' | 'error'>('checking');

  ngOnInit(): void {
    const orderId = this.orderId();
    let last: OrderDetail | null = null;
    defer(() => this.api.order(orderId))
      .pipe(
        repeat({ count: this.polling.attempts, delay: this.polling.intervalMs }),
        takeWhile((order) => awaitingPayment(order), true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (order) => (last = order),
        error: () => this.state.set('error'),
        complete: () => {
          if (last && !awaitingPayment(last)) {
            void this.router.navigate(['/orders', orderId], { replaceUrl: true });
          } else if (last) {
            this.state.set('slow');
          }
        },
      });
  }
}
