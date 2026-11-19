import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CartPage } from './cart-page';
import { CartStore } from './cart-store';

describe('CartPage', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  it('edits quantities within the limits, removes lines and shows no prices', async () => {
    const cart = TestBed.inject(CartStore);
    cart.add({
      slug: 'celebration-cake',
      productName: 'Celebration Cake',
      choices: ['Size: 2 kg', 'Message on the cake: <i>Hi</i>'],
      line: { productId: 'cake', quantity: 2, selectedOptionIds: ['two'], customTexts: {} },
    });
    const fixture = TestBed.createComponent(CartPage);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelectorAll('[data-testid="cart-item"]').length).toBe(1);
    expect(root.textContent).toContain('Message on the cake: <i>Hi</i>');
    expect(root.querySelector('i')).toBeNull();
    expect(root.textContent).not.toContain('৳');

    const quantity = root.querySelector<HTMLInputElement>('input[type="number"]');
    if (!quantity) {
      throw new Error('no quantity box');
    }
    quantity.value = '99';
    quantity.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(cart.items()[0].line.quantity).toBe(50);
    expect(quantity.value).toBe('50');

    [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Remove'))?.click();
    await fixture.whenStable();
    expect(root.textContent).toContain('Your cart is empty.');
  });
});
