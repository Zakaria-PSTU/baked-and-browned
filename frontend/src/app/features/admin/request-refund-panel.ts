import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { finalize } from 'rxjs';
import type { AdminPayment, Refund } from '../../core/api/generated/models';
import { userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { AdminRefundsApi } from './admin-refunds-api';

/** Refunds that still count against the payment and block a new request (ADR-0017). */
const OPEN = new Set(['REQUESTED', 'APPROVED']);

/**
 * Opens a refund request for one received payment, for example after a complaint about a
 * delivered order (N-05). Without an amount it covers everything not yet refunded; another ADMIN
 * must approve it before any money is sent. The server checks every limit again.
 */
@Component({
  selector: 'bb-request-refund-panel',
  imports: [BdtPipe],
  templateUrl: './request-refund-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RequestRefundPanel {
  private readonly api = inject(AdminRefundsApi);

  readonly payment = input.required<AdminPayment>();
  readonly refunds = input<Refund[]>([]);

  /** A request was opened; the order is read again. */
  readonly requested = output<string>();

  /** What is left to refund: the payment minus its completed refunds. */
  protected readonly remaining = computed(() => {
    const p = this.payment();
    return Math.round((p.amount - (p.refundedAmount ?? 0)) * 100) / 100;
  });

  /** Only a received payment with something left and no refund already in progress. */
  protected readonly available = computed(
    () =>
      this.payment().status === 'SUCCEEDED' &&
      this.remaining() > 0 &&
      !this.refunds().some((r) => r.paymentId === this.payment().id && OPEN.has(r.status)),
  );

  protected readonly open = signal(false);
  protected readonly reason = signal('');
  protected readonly amountText = signal('');
  protected readonly confirming = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected start(): void {
    this.open.set(true);
    this.error.set(null);
  }

  protected cancel(): void {
    this.open.set(false);
    this.confirming.set(null);
    this.error.set(null);
  }

  protected typeReason(event: Event): void {
    this.reason.set((event.target as HTMLTextAreaElement).value);
    this.error.set(null);
  }

  protected typeAmount(event: Event): void {
    this.amountText.set((event.target as HTMLInputElement).value);
    this.error.set(null);
  }

  /** Checks the form and asks for confirmation with the amount that will be requested. */
  protected review(): void {
    const reason = this.reason().trim();
    if (reason.length < 3 || reason.length > 500) {
      this.error.set('Give the reason for the refund (3 to 500 characters).');
      return;
    }
    const text = this.amountText().trim();
    let amount = this.remaining();
    if (text !== '') {
      if (!/^\d+(\.\d{1,2})?$/.test(text)) {
        this.error.set('Enter the amount in taka, for example 500 or 500.50.');
        return;
      }
      amount = Number(text);
      if (amount <= 0 || amount > this.remaining()) {
        this.error.set('The amount must be more than 0 and at most what is left to refund.');
        return;
      }
    }
    this.error.set(null);
    this.confirming.set(amount);
  }

  protected back(): void {
    this.confirming.set(null);
  }

  protected confirm(): void {
    const amount = this.confirming();
    if (amount === null || this.busy()) {
      return;
    }
    // A full refund is sent without an amount, so the server refunds exactly what is left.
    const partial =
      this.amountText().trim() !== '' && amount < this.remaining() ? amount : undefined;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .request(this.payment().id, this.reason().trim(), partial)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.open.set(false);
          this.confirming.set(null);
          this.reason.set('');
          this.amountText.set('');
          this.requested.emit(
            'A refund was requested. Another admin must approve it before any money is sent.',
          );
        },
        error: (err: unknown) => {
          this.confirming.set(null);
          this.error.set(userMessage(err, 'That did not work. Please try again.'));
        },
      });
  }
}
