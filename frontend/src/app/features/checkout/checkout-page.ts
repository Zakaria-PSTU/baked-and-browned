import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import type {
  CheckoutQuote,
  CheckoutQuoteRequest,
  DeliveryZone,
} from '../../core/api/generated/models';
import { AuthSession } from '../../core/auth/auth-session';
import { userMessage } from '../../core/http/problem';
import { BD_MOBILE_PATTERN, normalizeBdMobile } from '../auth/phone';
import { CartStore } from '../cart/cart-store';
import { BdtPipe, formatBdt } from '../../shared/bdt.pipe';
import { formatDhakaDay, formatDhakaRange, nextDhakaDates } from '../../shared/dhaka-time';
import { CheckoutApi } from './checkout-api';
import { forgetAttempt, idempotencyKeyFor } from './checkout-attempt';

/** How many days ahead customers can pick a delivery date. */
export const BOOKING_DAYS = 14;

/**
 * Checkout: delivery details, a slot, then a server quote the customer confirms before placing
 * the order. Cash on delivery only for now. Every amount shown comes from the server; any change
 * after a quote drops it, so the total placed is always the total reviewed.
 */
@Component({
  selector: 'bb-checkout-page',
  imports: [ReactiveFormsModule, RouterLink, BdtPipe],
  templateUrl: './checkout-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CheckoutPage {
  private readonly api = inject(CheckoutApi);
  private readonly router = inject(Router);
  protected readonly cart = inject(CartStore);

  protected readonly zones = rxResource({ stream: () => this.api.zones() });

  protected readonly dates = nextDhakaDates(new Date(), BOOKING_DAYS);
  protected readonly date = signal(this.dates[0]);
  protected readonly slots = rxResource({
    params: () => this.date(),
    stream: ({ params }) => this.api.slots(params),
  });
  protected readonly slotId = signal<string | null>(null);

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(150)],
    }),
    phone: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    area: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    address: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(5), Validators.maxLength(500)],
    }),
    notes: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
  });

  /** The server's quote for exactly the request shown; cleared by any change. */
  protected readonly quote = signal<CheckoutQuote | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly formatDay = formatDhakaDay;
  protected readonly formatRange = formatDhakaRange;

  constructor() {
    const user = inject(AuthSession).user();
    if (user) {
      this.form.patchValue({ name: user.fullName, phone: user.phone });
    }
    this.form.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => {
      this.quote.set(null);
    });
    // The cart can change in another tab while this page is open.
    effect(() => {
      this.cart.items();
      this.quote.set(null);
    });
  }

  /** "Dhanmondi · delivery ৳60.00 (orders from ৳500.00)". Amounts as the server sent them. */
  protected zoneLabel(zone: DeliveryZone): string {
    const minimum = zone.minOrderAmount ? ` (orders from ${formatBdt(zone.minOrderAmount)})` : '';
    return `${zone.name} · delivery ${formatBdt(zone.deliveryFee)}${minimum}`;
  }

  protected chooseDate(event: Event): void {
    this.date.set((event.target as HTMLSelectElement).value);
    this.slotId.set(null);
    this.quote.set(null);
  }

  protected chooseSlot(id: string): void {
    this.slotId.set(id);
    this.quote.set(null);
  }

  protected phoneInvalid(): boolean {
    const control = this.form.controls.phone;
    return control.touched && !BD_MOBILE_PATTERN.test(normalizeBdMobile(control.value));
  }

  protected review(): void {
    const request = this.request();
    if (!request || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .quote(request)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (quote) => this.quote.set(quote),
        error: (err: unknown) =>
          this.error.set(userMessage(err, 'We could not price your order. Please try again.')),
      });
  }

  protected place(): void {
    const request = this.request();
    if (!request || !this.quote() || this.busy()) {
      return;
    }
    const body = { ...request, paymentMethod: 'COD' as const };
    this.busy.set(true);
    this.error.set(null);
    this.api
      .place({ ...body, idempotencyKey: idempotencyKeyFor(body) })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (order) => {
          forgetAttempt();
          this.cart.clear();
          void this.router.navigate(['/orders', order.orderId], { queryParams: { placed: 1 } });
        },
        error: (err: unknown) => {
          // A slot or stock change since the quote makes it stale; price again before retrying.
          this.quote.set(null);
          this.error.set(userMessage(err, 'We could not place your order. Please try again.'));
        },
      });
  }

  /** The request, or null (marking fields) while something is missing or invalid. */
  private request(): CheckoutQuoteRequest | null {
    this.form.markAllAsTouched();
    const slotId = this.slotId();
    const items = this.cart.items();
    const v = this.form.getRawValue();
    const phone = normalizeBdMobile(v.phone);
    if (this.form.invalid || !BD_MOBILE_PATTERN.test(phone)) {
      this.error.set('Please check the delivery details below.');
      return null;
    }
    if (!slotId) {
      this.error.set('Please choose a delivery time.');
      return null;
    }
    if (items.length === 0) {
      this.error.set('Your cart is empty.');
      return null;
    }
    const notes = v.notes.trim();
    return {
      deliverySlotId: slotId,
      delivery: {
        name: v.name.trim(),
        phone,
        area: v.area,
        address: v.address.trim(),
        ...(notes ? { notes } : {}),
      },
      items: items.map((i) => i.line),
    };
  }
}
