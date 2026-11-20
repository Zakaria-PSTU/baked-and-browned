import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
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

  /**
   * The two queues are loaded by status, so an old open refund is never pushed out of the server's
   * 500-row list by newer ones; the history comes from the newest refunds.
   */
  protected readonly refunds = rxResource({
    stream: () =>
      forkJoin({
        requested: this.api.list('REQUESTED'),
        approved: this.api.list('APPROVED'),
        recent: this.api.list(),
      }),
  });

  protected readonly toApprove = computed(() =>
    [...(this.refunds.value()?.requested ?? [])].sort(oldestFirst),
  );
  protected readonly toPayOut = computed(() =>
    [...(this.refunds.value()?.approved ?? [])].sort(oldestFirst),
  );
  /** Newest first, as the server lists them. */
  protected readonly history = computed(() =>
    (this.refunds.value()?.recent ?? [])
      .filter((r) => r.status === 'COMPLETED' || r.status === 'REJECTED')
      .slice(0, HISTORY_SIZE),
  );

  protected readonly at = formatDhakaDateTime;

  /** A request rejected by the admin who made it was withdrawn, not refused. */
  protected outcome(r: Refund): string {
    if (r.status === 'COMPLETED') {
      return 'Sent';
    }
    return r.requestedByType === 'ADMIN' && !!r.decidedBy && r.decidedBy === r.requestedBy
      ? 'Withdrawn'
      : 'Rejected';
  }

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
