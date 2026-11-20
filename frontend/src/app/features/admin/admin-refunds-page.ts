import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import type { Refund } from '../../core/api/generated/models';
import { AuthSession } from '../../core/auth/auth-session';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { AdminRefundsApi } from './admin-refunds-api';
import { RefundCard } from './refund-card';

/** How many finished refunds the history shows. */
export const HISTORY_SIZE = 20;

const oldestFirst = (a: Refund, b: Refund) => a.createdAt.localeCompare(b.createdAt);

/**
 * ADMIN refund work (N-05, ADR-0017): requests to approve, approved refunds to pay out (oldest
 * first), and recent finished ones. Money is sent outside the app; this page records decisions and
 * references. Requests are opened from an order's page or a reconciliation case.
 */
@Component({
  selector: 'bb-admin-refunds-page',
  imports: [RouterLink, RefundCard, BdtPipe],
  templateUrl: './admin-refunds-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminRefundsPage {
  private readonly api = inject(AdminRefundsApi);
  private readonly session = inject(AuthSession);

  protected readonly me = computed(() => this.session.user()?.id ?? null);

  protected readonly refunds = rxResource({ stream: () => this.api.list() });

  protected readonly toApprove = computed(() =>
    (this.refunds.value() ?? []).filter((r) => r.status === 'REQUESTED').sort(oldestFirst),
  );
  protected readonly toPayOut = computed(() =>
    (this.refunds.value() ?? []).filter((r) => r.status === 'APPROVED').sort(oldestFirst),
  );
  /** Newest first, as the server lists them. */
  protected readonly history = computed(() =>
    (this.refunds.value() ?? [])
      .filter((r) => r.status === 'COMPLETED' || r.status === 'REJECTED')
      .slice(0, HISTORY_SIZE),
  );

  protected readonly at = formatDhakaDateTime;
  protected readonly lastMessage = signal<string | null>(null);

  protected changed(event: { message: string }): void {
    this.lastMessage.set(event.message);
    this.refunds.reload();
  }

  protected outdated(event: { orderNumber: string }): void {
    this.lastMessage.set(
      `The refund for order ${event.orderNumber} was already handled by someone else, so the list was refreshed.`,
    );
    this.refunds.reload();
  }

  protected reload(): void {
    this.lastMessage.set(null);
    this.refunds.reload();
  }
}
