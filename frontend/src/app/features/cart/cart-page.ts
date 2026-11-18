import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CartStore, MAX_QUANTITY } from './cart-store';

/** The cart: lines and quantities only. Prices are calculated by the server at checkout. */
@Component({
  selector: 'bb-cart-page',
  imports: [RouterLink],
  templateUrl: './cart-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CartPage {
  protected readonly cart = inject(CartStore);
  protected readonly maxQuantity = MAX_QUANTITY;

  protected setQuantity(id: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    this.cart.setQuantity(id, Number(input.value));
    // Show what was kept (clamped to 1–50), even when it equals the previous value.
    const kept = this.cart.items().find((i) => i.id === id)?.line.quantity;
    if (kept !== undefined) {
      input.value = String(kept);
    }
  }
}
