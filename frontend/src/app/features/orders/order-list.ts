import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { Order } from '../../core/api/generated/models';
import { BdtPipe } from '../../shared/bdt.pipe';
import { formatDhakaDateTime } from '../../shared/dhaka-time';
import { statusLabel } from './order-status';

/** Rows of order summaries, each linking to the order. */
@Component({
  selector: 'bb-order-list',
  imports: [RouterLink, BdtPipe],
  template: `
    <ul class="mt-3 divide-y divide-stone-200 rounded-lg border border-stone-200 bg-white">
      @for (o of orders(); track o.orderId) {
        <li data-testid="order-row">
          <a
            [routerLink]="['/orders', o.orderId]"
            class="flex flex-wrap items-center justify-between gap-2 p-4 hover:bg-stone-50"
          >
            <span>
              <span class="block font-medium">Order {{ o.orderNumber }}</span>
              @if (o.createdAt) {
                <span class="block text-sm text-stone-600">Placed {{ placed(o.createdAt) }}</span>
              }
            </span>
            <span class="text-right">
              <span
                class="block text-sm font-medium"
                [class.text-amber-800]="o.status === 'PENDING_PAYMENT'"
                data-testid="row-status"
                >{{ label(o) }}</span
              >
              <span class="block tabular-nums">{{ o.total | bdt }}</span>
            </span>
          </a>
        </li>
      }
    </ul>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderList {
  readonly orders = input.required<readonly Order[]>();

  protected readonly placed = formatDhakaDateTime;

  protected label(order: Order): string {
    return statusLabel(order.status);
  }
}
