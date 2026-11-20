import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, finalize } from 'rxjs';
import type { Refund } from '../../core/api/generated/models';
import { problemOf, userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { AdminRefundsApi } from './admin-refunds-api';

type Step = 'approve' | 'reject' | 'complete';

/** Server codes after which this card no longer shows the refund as it is. */
const OUTDATED = new Set(['REFUND_ALREADY_DECIDED', 'REFUND_NOT_APPROVED', 'REFUND_NOT_FOUND']);

/**
 * One refund waiting for an ADMIN (ADR-0017). REQUESTED: approve (never by whoever requested it)
 * or reject / withdraw, each with a note. APPROVED: record the reference of money already sent,
 * which tells the customer by SMS. Every action is confirmed first and checked again by the server.
 */
@Component({
  selector: 'bb-refund-card',
  imports: [RouterLink, BdtPipe],
  templateUrl: './refund-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RefundCard {
  private readonly api = inject(AdminRefundsApi);

  readonly refund = input.required<Refund>();
  /** The signed-in ADMIN's user id. */
  readonly me = input<string | null>(null);

  /** The refund moved on here; the list reloads. */
  readonly changed = output<{ orderNumber: string; message: string }>();
  /** Someone else moved it first; the list reloads. */
  readonly outdated = output<{ orderNumber: string }>();

  protected readonly mine = computed(() => {
    const me = this.me();
    return !!me && this.refund().requestedBy === me;
  });

  protected readonly requester = computed(() => {
    const r = this.refund();
    if (r.requestedByType === 'CUSTOMER') {
      return 'the customer, by cancelling';
    }
    if (r.requestedByType === 'SYSTEM') {
      return 'the system';
    }
    return this.mine() ? 'you' : 'another admin';
  });

  protected readonly orderLabel = computed(
    () => this.refund().orderNumber ?? this.refund().orderId,
  );

  protected readonly at = formatDhakaDateTime;
  protected readonly note = signal('');
  protected readonly reference = signal('');
  protected readonly confirming = signal<Step | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected typeNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
    this.error.set(null);
  }

  protected typeReference(event: Event): void {
    this.reference.set((event.target as HTMLInputElement).value);
    this.error.set(null);
  }

  protected choose(step: Step): void {
    this.error.set(null);
    if (step === 'complete') {
      const ref = this.reference().trim();
      if (ref.length < 3 || ref.length > 150) {
        this.error.set(
          'Enter the refund reference from bKash, the bank or the gateway (3 to 150 characters).',
        );
        return;
      }
    } else {
      const note = this.note().trim();
      if (note.length < 3 || note.length > 500) {
        this.error.set('Write a note for the decision (3 to 500 characters).');
        return;
      }
    }
    this.confirming.set(step);
  }

  protected back(): void {
    this.confirming.set(null);
  }

  protected confirm(): void {
    const step = this.confirming();
    const r = this.refund();
    if (!step || this.busy()) {
      return;
    }
    const order = this.orderLabel();
    let action: Observable<Refund>;
    let message: string;
    switch (step) {
      case 'approve':
        action = this.api.decide(r.id, 'APPROVE', this.note().trim());
        message = `The refund for order ${order} is approved. Send the money, then record its reference.`;
        break;
      case 'reject':
        action = this.api.decide(r.id, 'REJECT', this.note().trim());
        message = this.mine()
          ? `Your refund request for order ${order} is withdrawn.`
          : `The refund for order ${order} is rejected.`;
        break;
      case 'complete':
        action = this.api.complete(r.id, this.reference().trim());
        message = `The refund for order ${order} is recorded as sent; the customer is told by SMS.`;
        break;
    }
    this.busy.set(true);
    this.error.set(null);
    action.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => {
        this.confirming.set(null);
        this.changed.emit({ orderNumber: order, message });
      },
      error: (err: unknown) => {
        this.confirming.set(null);
        const code = problemOf(err)?.code;
        if (code && OUTDATED.has(code)) {
          this.outdated.emit({ orderNumber: order });
          return;
        }
        if (code === 'SECOND_APPROVER_REQUIRED') {
          this.error.set('You requested this refund, so another admin must approve it.');
          return;
        }
        this.error.set(userMessage(err, 'That did not work. Please try again.'));
      },
    });
  }
}
