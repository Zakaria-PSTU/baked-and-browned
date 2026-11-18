import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { finalize } from 'rxjs';
import type { AdminPayment } from '../../core/api/generated/models';
import { userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { AdminPaymentsApi } from './admin-payments-api';

const PROVIDER_NAMES: Record<string, string> = {
  BANK_TRANSFER: 'Bank transfer',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
  ROCKET: 'Rocket',
  OTHER: 'Other',
};

const EXTENSIONS: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'application/pdf': 'pdf',
};

/**
 * One MANUAL payment waiting for an ADMIN decision (ADR-0011). The customer's reference and proof
 * are claims, not evidence: the ADMIN checks the bank/MFS statement and records what was checked.
 * Approving confirms the order; rejecting fails the payment and cancels the order, so it takes a
 * second click.
 */
@Component({
  selector: 'bb-payment-review-card',
  imports: [BdtPipe],
  templateUrl: './payment-review-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentReviewCard {
  private readonly api = inject(AdminPaymentsApi);

  readonly payment = input.required<AdminPayment>();

  /** A decision was recorded; the list reloads. */
  readonly decided = output<{ orderNumber: string; decision: 'APPROVE' | 'REJECT' }>();

  protected readonly note = signal('');
  protected readonly confirmingReject = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Object URL of a proof image shown inline (images only; PDFs are downloaded). */
  protected readonly proofImage = signal<string | null>(null);
  protected readonly proofLoading = signal(false);
  private readonly urls: string[] = [];

  protected readonly formatDateTime = formatDhakaDateTime;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.urls.forEach((u) => URL.revokeObjectURL(u)));
  }

  protected providerName(provider: string | null | undefined): string {
    return provider ? (PROVIDER_NAMES[provider] ?? provider) : 'Not given';
  }

  protected typeNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
    this.error.set(null);
  }

  protected showProof(): void {
    if (this.proofLoading()) {
      return;
    }
    this.proofLoading.set(true);
    this.error.set(null);
    this.api
      .proof(this.payment().id)
      .pipe(finalize(() => this.proofLoading.set(false)))
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          this.urls.push(url);
          if (blob.type === 'image/jpeg' || blob.type === 'image/png') {
            this.proofImage.set(url);
          } else {
            // Never rendered in the app's origin: saved as a file instead.
            const link = document.createElement('a');
            link.href = url;
            link.download = `payment-proof-${this.payment().orderNumber}.${EXTENSIONS[blob.type] ?? 'bin'}`;
            link.click();
          }
        },
        error: (err: unknown) =>
          this.error.set(userMessage(err, 'We could not load the proof file.')),
      });
  }

  protected approve(): void {
    this.decide('APPROVE');
  }

  protected reject(): void {
    if (!this.confirmingReject()) {
      this.confirmingReject.set(true);
      return;
    }
    this.decide('REJECT');
  }

  protected cancelReject(): void {
    this.confirmingReject.set(false);
  }

  private decide(decision: 'APPROVE' | 'REJECT'): void {
    const note = this.note().trim();
    if (note.length < 3 || note.length > 500) {
      this.error.set('Write what you checked (3 to 500 characters), e.g. the statement line.');
      return;
    }
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .verify(this.payment().id, decision, note)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => this.decided.emit({ orderNumber: this.payment().orderNumber, decision }),
        error: (err: unknown) => {
          this.confirmingReject.set(false);
          this.error.set(userMessage(err, 'We could not record the decision. Please try again.'));
        },
      });
  }
}
