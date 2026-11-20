import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import type { ReconciliationCase } from '../../core/api/generated/models';
import { problemOf, userMessage } from '../../core/http/problem';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { AdminReconciliationApi, type ResolutionAction } from './admin-reconciliation-api';

const CASE_TYPES: Record<ReconciliationCase['caseType'], string> = {
  LATE_SUCCESS: 'Paid after the order ended',
  AMOUNT_MISMATCH: 'Paid amount does not match the order',
  AMBIGUOUS_STATUS: 'Payment outcome unknown',
};

const REASONS: Record<string, string> = {
  PAID_AFTER_ORDER_EXPIRED: 'The payment arrived after the order expired.',
  HIGH_RISK: 'The gateway flagged the payment for review.',
  SUCCESS_ON_REPLACED_ATTEMPT: 'An earlier, replaced payment attempt succeeded.',
  GATEWAY_STATUS_UNKNOWN: 'The gateway never confirmed whether the customer paid.',
};

/** What each action does, shown before the ADMIN confirms it. */
export const ACTIONS: Record<ResolutionAction, { label: string; confirm: string; effect: string }> =
  {
    REINSTATE: {
      label: 'Reinstate order',
      confirm: 'Yes, reinstate the order',
      effect:
        'Confirms the order again. Its delivery slot and stock are reserved again only if they ' +
        'are still available; otherwise nothing changes.',
    },
    REQUEST_REFUND: {
      label: 'Request refund',
      confirm: 'Yes, request a refund',
      effect:
        'Opens a refund request for what is left of this payment. This is not a refund and no ' +
        'money is sent: another admin must approve the request, and the refund is then paid and ' +
        'recorded separately.',
    },
    NO_ACTION: {
      label: 'Close without action',
      confirm: 'Yes, close the case',
      effect:
        'Closes the case without changing the order or the payment, for example after settling ' +
        'it with the provider or the customer. The customer no longer sees the payment as under ' +
        'review.',
    },
  };

/** Server codes after which the case is no longer what this card shows. */
const OUTDATED = new Set(['CASE_ALREADY_RESOLVED', 'CASE_NOT_FOUND']);
/** A reinstatement refused because the slot or stock is gone (nothing changed). */
const NO_ROOM = new Set(['SLOT_UNAVAILABLE', 'INSUFFICIENT_STOCK']);

/**
 * One open payment reconciliation case (ADR-0008). The ADMIN picks an action, writes a note and
 * confirms; the server checks everything again under locks and audits the resolution.
 */
@Component({
  selector: 'bb-reconciliation-case-card',
  imports: [RouterLink],
  templateUrl: './reconciliation-case-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReconciliationCaseCard {
  private readonly api = inject(AdminReconciliationApi);

  readonly reconciliationCase = input.required<ReconciliationCase>();

  /** The case was resolved here; the list reloads. */
  readonly resolved = output<{ orderNumber: string; action: ResolutionAction }>();
  /** The case was resolved elsewhere or no longer exists; the list reloads. */
  readonly outdated = output<{ orderNumber: string }>();

  protected readonly actions = ACTIONS;
  protected readonly order: ResolutionAction[] = ['REINSTATE', 'REQUEST_REFUND', 'NO_ACTION'];
  protected readonly at = formatDhakaDateTime;

  protected readonly note = signal('');
  protected readonly choosing = signal<ResolutionAction | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected typeLabel(c: ReconciliationCase): string {
    return CASE_TYPES[c.caseType] ?? c.caseType;
  }

  protected reasonLabel(c: ReconciliationCase): string | null {
    return c.reason ? (REASONS[c.reason] ?? c.reason) : null;
  }

  protected orderLabel(c: ReconciliationCase): string {
    return c.orderNumber ?? c.orderId;
  }

  protected typeNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
    this.error.set(null);
  }

  protected choose(action: ResolutionAction): void {
    this.error.set(null);
    this.choosing.set(action);
  }

  protected keep(): void {
    this.choosing.set(null);
  }

  protected confirm(): void {
    const action = this.choosing();
    const c = this.reconciliationCase();
    if (!action || this.busy()) {
      return;
    }
    const note = this.note().trim();
    if (note.length < 3 || note.length > 500) {
      this.error.set('Write a note of what you checked or agreed (3 to 500 characters).');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .resolve(c.id, action, note)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.choosing.set(null);
          this.resolved.emit({ orderNumber: this.orderLabel(c), action });
        },
        error: (err: unknown) => {
          this.choosing.set(null);
          const code = problemOf(err)?.code;
          if (code && OUTDATED.has(code)) {
            this.outdated.emit({ orderNumber: this.orderLabel(c) });
            return;
          }
          const message = userMessage(err, 'That did not work. Please try again.');
          this.error.set(
            code && NO_ROOM.has(code)
              ? `${message} Nothing was changed. You can request a refund instead.`
              : message,
          );
        },
      });
  }
}
