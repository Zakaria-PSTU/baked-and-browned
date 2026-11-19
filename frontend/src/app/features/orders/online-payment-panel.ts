import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import type { OrderDetail } from '../../core/api/generated/models';
import { userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { ExternalNavigation } from '../../shared/external-navigation';
import { CheckoutApi } from '../checkout/checkout-api';
import { timeLeft } from './manual-payment-panel';

/**
 * Paying an ONLINE order that is still waiting for payment: the time left on the hold (ADR-0008)
 * and a button to the provider's hosted page. Coming back from that page proves nothing; the order
 * shows as paid only once the server has verified the provider's callback.
 */
@Component({
  selector: 'bb-online-payment-panel',
  imports: [BdtPipe],
  templateUrl: './online-payment-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OnlinePaymentPanel {
  private readonly api = inject(CheckoutApi);
  private readonly external = inject(ExternalNavigation);

  readonly order = input.required<OrderDetail>();

  /** The last attempt failed or was cancelled on the provider's page. */
  protected readonly lastAttemptFailed = computed(() => {
    const status = this.order().payment.status;
    return status === 'FAILED' || status === 'CANCELLED';
  });

  private readonly now = signal(Date.now());
  protected readonly remaining = computed(() => {
    const deadline = this.order().expiresAt;
    return deadline ? timeLeft(deadline, this.now()) : null;
  });

  /** Stays true once the browser is on its way to the provider. */
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected pay(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.startOnlinePayment(this.order().orderId).subscribe({
      next: (url) => {
        try {
          this.external.go(url);
        } catch {
          this.busy.set(false);
          this.error.set('We could not open the payment page. Please try again.');
        }
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(userMessage(err, 'We could not open the payment page. Please try again.'));
      },
    });
  }
}
