import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import type { OrderStatus } from '../../core/api/generated/models';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { ADMIN_LISTED, AdminOrdersApi } from './admin-orders-api';
import { ADMIN_STATUS_LABELS } from './order-transitions';

/** Filters in working order: what needs doing first. */
export const FILTERS: readonly OrderStatus[] = [
  'CONFIRMED',
  'PREPARING',
  'READY',
  'OUT_FOR_DELIVERY',
  'PENDING_PAYMENT',
  'DELIVERED',
  'CANCELLED',
  'EXPIRED',
];

/** ADMIN orders, newest first, optionally one status (`?status=`, so it survives a reload). */
@Component({
  selector: 'bb-admin-orders-page',
  imports: [RouterLink, BdtPipe],
  templateUrl: './admin-orders-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminOrdersPage {
  private readonly api = inject(AdminOrdersApi);
  private readonly router = inject(Router);

  /** `?status=`; anything that is not a status means all orders. */
  readonly status = input<string | undefined>();

  protected readonly filter = computed<OrderStatus | undefined>(() => {
    const s = this.status();
    return FILTERS.includes(s as OrderStatus) ? (s as OrderStatus) : undefined;
  });

  protected readonly orders = rxResource({
    params: () => ({ status: this.filter() }),
    stream: ({ params }) => this.api.list(params.status),
  });

  protected readonly truncated = computed(() => (this.orders.value() ?? []).length >= ADMIN_LISTED);

  protected readonly filters = FILTERS;
  protected readonly labels = ADMIN_STATUS_LABELS;
  protected readonly listed = ADMIN_LISTED;
  protected readonly placed = formatDhakaDateTime;

  protected choose(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    void this.router.navigate(['/admin/orders'], {
      queryParams: { status: value || undefined },
    });
  }
}
