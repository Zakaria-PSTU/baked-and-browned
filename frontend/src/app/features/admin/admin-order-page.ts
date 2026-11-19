import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, finalize } from 'rxjs';
import type { AdminPayment } from '../../core/api/generated/models';
import { userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaDateTime, formatDhakaRange } from '../../shared/dhaka-time';
import { AdminOrdersApi } from './admin-orders-api';
import { AdminPaymentsApi } from './admin-payments-api';
import { ADMIN_STATUS_LABELS, CANCELLABLE, NEXT_STEP } from './order-transitions';

type Pending = 'step' | 'cancel' | 'cash';

/**
 * One order for an ADMIN: customer, delivery, items, payments, refunds and the full history, with
 * the actions its status allows. Fulfillment steps go one at a time; cancelling needs a reason and
 * a second click; recording cash for a COD order needs a note. Every action is checked and audited
 * by the server, and the order is read again afterwards.
 */
@Component({
  selector: 'bb-admin-order-page',
  imports: [RouterLink, BdtPipe],
  templateUrl: './admin-order-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminOrderPage {
  private readonly orders = inject(AdminOrdersApi);
  private readonly payments = inject(AdminPaymentsApi);

  /** Route parameter `:orderId`. */
  readonly orderId = input.required<string>();

  protected readonly order = rxResource({
    params: () => this.orderId(),
    stream: ({ params }) => this.orders.get(params),
  });

  protected readonly notFound = computed(() => {
    const error = this.order.error();
    const cause = error && 'cause' in error ? error.cause : error;
    return cause instanceof HttpErrorResponse && cause.status === 404;
  });

  protected readonly next = computed(() => {
    const o = this.order.value();
    return o ? NEXT_STEP[o.status] : undefined;
  });
  protected readonly cancellable = computed(() => {
    const o = this.order.value();
    return !!o && CANCELLABLE.has(o.status);
  });
  /** The COD payment still waiting to be collected, if any. */
  protected readonly uncollectedCash = computed<AdminPayment | undefined>(() => {
    const o = this.order.value();
    if (!o || o.status === 'CANCELLED' || o.status === 'EXPIRED') {
      return undefined;
    }
    return o.payments.find((p) => p.method === 'COD' && p.status === 'PENDING');
  });

  protected readonly cancelReason = signal('');
  protected readonly confirmingCancel = signal(false);
  protected readonly cashNote = signal('');
  protected readonly busy = signal<Pending | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);

  protected readonly labels = ADMIN_STATUS_LABELS;
  protected readonly at = formatDhakaDateTime;
  protected readonly range = formatDhakaRange;

  protected typeReason(event: Event): void {
    this.cancelReason.set((event.target as HTMLTextAreaElement).value);
  }

  protected typeCashNote(event: Event): void {
    this.cashNote.set((event.target as HTMLInputElement).value);
  }

  protected advance(): void {
    const o = this.order.value();
    const step = this.next();
    if (!o || !step) {
      return;
    }
    this.run(
      'step',
      this.orders.changeStatus(o.orderId, step.status),
      `Now ${this.labels[step.status].toLowerCase()}.`,
    );
  }

  protected cancel(): void {
    const o = this.order.value();
    if (!o) {
      return;
    }
    const reason = this.cancelReason().trim();
    if (reason.length < 3 || reason.length > 500) {
      this.error.set('Give the reason for cancelling (3 to 500 characters).');
      return;
    }
    if (!this.confirmingCancel()) {
      this.error.set(null);
      this.confirmingCancel.set(true);
      return;
    }
    this.run(
      'cancel',
      this.orders.changeStatus(o.orderId, 'CANCELLED', reason),
      'The order is cancelled.',
    );
  }

  protected keep(): void {
    this.confirmingCancel.set(false);
  }

  protected collectCash(): void {
    const payment = this.uncollectedCash();
    const note = this.cashNote().trim();
    if (!payment) {
      return;
    }
    if (note.length < 3 || note.length > 500) {
      this.error.set('Note who collected the cash (3 to 500 characters).');
      return;
    }
    this.run(
      'cash',
      this.payments.verify(payment.id, 'APPROVE', note),
      'Cash collection recorded.',
    );
  }

  private run(kind: Pending, action: Observable<unknown>, message: string): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(kind);
    this.error.set(null);
    this.done.set(null);
    action.pipe(finalize(() => this.busy.set(null))).subscribe({
      next: () => {
        this.done.set(message);
        this.confirmingCancel.set(false);
        this.cancelReason.set('');
        this.cashNote.set('');
        this.order.reload();
      },
      error: (err: unknown) => {
        this.confirmingCancel.set(false);
        // Someone else may have moved the order: show it as it is now.
        this.order.reload();
        this.error.set(userMessage(err, 'That did not work. Please try again.'));
      },
    });
  }
}
