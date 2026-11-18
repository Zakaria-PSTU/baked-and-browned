import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AdminPaymentsApi } from './admin-payments-api';
import { PaymentReviewCard } from './payment-review-card';

export const PAGE_SIZE = 20;

/** ADMIN queue of MANUAL payments waiting for verification, oldest submission first. */
@Component({
  selector: 'bb-admin-payments-page',
  imports: [RouterLink, PaymentReviewCard],
  templateUrl: './admin-payments-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminPaymentsPage {
  private readonly api = inject(AdminPaymentsApi);

  protected readonly page = signal(0);
  protected readonly payments = rxResource({
    params: () => this.page(),
    stream: ({ params }) => this.api.awaitingVerification(params, PAGE_SIZE),
  });

  protected readonly pages = computed(() =>
    Math.max(1, Math.ceil((this.payments.value()?.totalElements ?? 0) / PAGE_SIZE)),
  );

  /** The last decision, confirmed to the ADMIN after the card leaves the queue. */
  protected readonly lastDecision = signal<string | null>(null);

  protected decided(event: { orderNumber: string; decision: 'APPROVE' | 'REJECT' }): void {
    this.lastDecision.set(
      event.decision === 'APPROVE'
        ? `Order ${event.orderNumber} is confirmed.`
        : `Order ${event.orderNumber} was cancelled and its payment rejected.`,
    );
    // The decided payment leaves the queue; stay on a page that still exists.
    const total = (this.payments.value()?.totalElements ?? 1) - 1;
    const last = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);
    if (this.page() > last) {
      this.page.set(last);
    } else {
      this.payments.reload();
    }
  }

  protected go(page: number): void {
    this.page.set(page);
    this.lastDecision.set(null);
  }

  protected reload(): void {
    this.lastDecision.set(null);
    this.payments.reload();
  }
}
