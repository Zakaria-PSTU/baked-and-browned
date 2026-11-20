import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AdminReconciliationApi, type ResolutionAction } from './admin-reconciliation-api';
import { ReconciliationCaseCard } from './reconciliation-case-card';

/** The confirmation shown after a case leaves the queue. */
export function resolvedMessage(orderNumber: string, action: ResolutionAction): string {
  switch (action) {
    case 'REINSTATE':
      return `Order ${orderNumber} is confirmed again.`;
    case 'REQUEST_REFUND':
      return (
        `A refund was requested for order ${orderNumber}. It is not paid yet: another admin ` +
        'must approve it first.'
      );
    case 'NO_ACTION':
      return `The case for order ${orderNumber} is closed without changes.`;
  }
}

/**
 * ADMIN queue of open payment reconciliation cases (ADR-0008, N-06), oldest first: late payments
 * on expired or cancelled orders, amount mismatches and unknown gateway outcomes. Refund approval
 * and completion are not on this page (N-05).
 */
@Component({
  selector: 'bb-admin-reconciliation-page',
  imports: [RouterLink, ReconciliationCaseCard],
  templateUrl: './admin-reconciliation-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminReconciliationPage {
  private readonly api = inject(AdminReconciliationApi);

  protected readonly cases = rxResource({ stream: () => this.api.open() });

  /** The last outcome, shown after the case leaves the queue. */
  protected readonly lastMessage = signal<string | null>(null);

  protected resolved(event: { orderNumber: string; action: ResolutionAction }): void {
    this.lastMessage.set(resolvedMessage(event.orderNumber, event.action));
    this.cases.reload();
  }

  protected outdated(event: { orderNumber: string }): void {
    this.lastMessage.set(
      `The case for order ${event.orderNumber} was already resolved, so the list was refreshed.`,
    );
    this.cases.reload();
  }

  protected reload(): void {
    this.lastMessage.set(null);
    this.cases.reload();
  }
}
