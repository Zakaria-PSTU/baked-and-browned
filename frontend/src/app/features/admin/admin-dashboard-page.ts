import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { formatBdt } from '../../shared/bdt.pipe';
import { AdminDashboardApi } from './admin-dashboard-api';

interface Tile {
  readonly label: string;
  readonly value: string;
  readonly hint: string;
  /** Needs an ADMIN's attention when non-zero. */
  readonly attention: boolean;
  /** Where the queue is worked, if it has a screen yet. */
  readonly link?: string;
}

/**
 * ADMIN overview of the current Asia/Dhaka day and the open work queues. Figures come from the
 * server as-is (the definitions live in the API contract); this page only formats them.
 */
@Component({
  selector: 'bb-admin-dashboard-page',
  imports: [RouterLink],
  templateUrl: './admin-dashboard-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminDashboardPage {
  private readonly api = inject(AdminDashboardApi);

  protected readonly summary = rxResource({ stream: () => this.api.summary() });

  protected readonly today = computed<Tile[]>(() => {
    const s = this.summary.value();
    if (!s) {
      return [];
    }
    return [
      tile('Orders today', String(s.ordersToday), 'Booked orders placed today', false),
      tile('Revenue today', formatBdt(s.revenueToday), 'Total of those orders', false),
      tile('Collected today', formatBdt(s.collectedToday ?? 0), 'Payments received today', false),
    ];
  });

  protected readonly queues = computed<Tile[]>(() => {
    const s = this.summary.value();
    if (!s) {
      return [];
    }
    const verification = s.awaitingPaymentVerification ?? 0;
    const reconciliation = s.openReconciliationCases ?? 0;
    const lowStock = s.lowStockIngredients ?? 0;
    return [
      tile('To prepare', String(s.pendingOrders), 'Confirmed, not started', false),
      tile('Preparing', String(s.preparingOrders), 'In the kitchen', false),
      tile(
        'Payments to verify',
        String(verification),
        'Manual submissions',
        verification > 0,
        '/admin/payments',
      ),
      tile('Reconciliation', String(reconciliation), 'Open cases', reconciliation > 0),
      tile('Low stock', String(lowStock), 'Ingredients at or below threshold', lowStock > 0),
    ];
  });

  protected reload(): void {
    this.summary.reload();
  }
}

function tile(label: string, value: string, hint: string, attention: boolean, link?: string): Tile {
  return { label, value, hint, attention, link };
}
