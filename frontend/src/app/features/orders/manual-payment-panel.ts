import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import type { ManualPaymentProvider, OrderDetail } from '../../core/api/generated/models';
import { userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { CheckoutApi } from '../checkout/checkout-api';

/** The server's limits (ADR-0011, contract `submitManualPayment`). */
export const PROOF_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];
export const PROOF_MAX_BYTES = 5 * 1024 * 1024;

const PROVIDER_NAMES: Record<ManualPaymentProvider, string> = {
  BANK_TRANSFER: 'Bank transfer',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
  ROCKET: 'Rocket',
  OTHER: 'Other',
};

/** "mm:ss" left until `deadline`, or null once it has passed. */
export function timeLeft(deadline: string, now: number): string | null {
  const ms = new Date(deadline).getTime() - now;
  if (!(ms > 0)) {
    return null;
  }
  const seconds = Math.ceil(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}

/**
 * Paying a MANUAL order: where to send the money, the time left on the hold (ADR-0008), and the
 * form for the transaction reference and an optional proof file. Submitting never confirms the
 * order; an ADMIN checks the statement first (ADR-0011).
 */
@Component({
  selector: 'bb-manual-payment-panel',
  imports: [BdtPipe],
  templateUrl: './manual-payment-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ManualPaymentPanel {
  private readonly api = inject(CheckoutApi);

  readonly order = input.required<OrderDetail>();

  /** The reference was recorded; the parent reloads the order. */
  readonly submitted = output<void>();

  protected readonly accounts = rxResource({ stream: () => this.api.manualAccounts() });

  /** Providers the business receives on, in the configured order. */
  protected readonly providers = computed(() => [
    ...new Set((this.accounts.value() ?? []).map((a) => a.provider)),
  ]);

  private readonly now = signal(Date.now());
  protected readonly remaining = computed(() => {
    const deadline = this.order().expiresAt;
    return deadline ? timeLeft(deadline, this.now()) : null;
  });

  protected readonly provider = signal<ManualPaymentProvider | ''>('');
  protected readonly reference = signal('');
  protected readonly proof = signal<File | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected providerName(provider: ManualPaymentProvider): string {
    return PROVIDER_NAMES[provider] ?? provider;
  }

  /** The chosen provider, or the only one when there is no choice to make. */
  private chosenProvider(): ManualPaymentProvider | null {
    const providers = this.providers();
    return this.provider() || (providers.length === 1 ? providers[0] : null);
  }

  protected chooseProvider(event: Event): void {
    this.provider.set((event.target as HTMLSelectElement).value as ManualPaymentProvider);
  }

  protected typeReference(event: Event): void {
    this.reference.set((event.target as HTMLInputElement).value);
  }

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.error.set(null);
    if (file && !PROOF_TYPES.includes(file.type)) {
      this.error.set('The proof must be a JPEG, PNG or PDF file.');
      input.value = '';
      this.proof.set(null);
    } else if (file && file.size > PROOF_MAX_BYTES) {
      this.error.set('The proof file can be at most 5 MB.');
      input.value = '';
      this.proof.set(null);
    } else {
      this.proof.set(file);
    }
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.busy()) {
      return;
    }
    const provider = this.chosenProvider();
    const reference = this.reference().trim();
    if (!provider) {
      this.error.set('Choose how you paid.');
      return;
    }
    if (reference.length < 3 || reference.length > 100) {
      this.error.set('Enter the transaction ID from your payment receipt.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .submitManual(this.order().orderId, provider, reference, this.proof() ?? undefined)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => this.submitted.emit(),
        error: (err: unknown) =>
          this.error.set(userMessage(err, 'We could not record your payment. Please try again.')),
      });
  }
}
