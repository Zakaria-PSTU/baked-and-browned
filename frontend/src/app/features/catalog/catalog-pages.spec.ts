import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { Product } from '../../core/api/generated/models';
import { formatBdt } from '../../shared/bdt.pipe';
import { CartStore } from '../cart/cart-store';
import { CatalogPage } from './catalog-page';
import { ProductPage } from './product-page';

const product = (overrides: Partial<Product> = {}): Product => ({
  id: 'p1',
  name: 'Fudge Brownie Box',
  slug: 'fudge-brownie-box',
  categoryId: 'c1',
  basePrice: 1250,
  currency: 'BDT',
  minLeadHours: 24,
  cancellationCutoffHours: 24,
  customizationGroups: [],
  disallowedCombinations: [],
  ...overrides,
});

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('formatBdt', () => {
  it('uses the taka sign, two decimals and South Asian grouping', () => {
    expect(formatBdt(1250)).toBe('৳1,250.00');
    expect(formatBdt(125000)).toBe('৳1,25,000.00');
    expect(formatBdt(0)).toBe('৳0.00');
  });
});

describe('catalog pages', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('CatalogPage', () => {
    it('loads categories and the filtered page from the URL state', async () => {
      const fixture = TestBed.createComponent(CatalogPage);
      fixture.componentRef.setInput('category', 'brownies');
      fixture.componentRef.setInput('q', 'fudge');
      fixture.componentRef.setInput('page', '1');
      await settle();

      http.expectOne('/api/v1/categories').flush([
        { id: 'c1', name: 'Brownies', slug: 'brownies' },
        { id: 'c2', name: 'Cookies', slug: 'cookies' },
      ]);
      const req = http.expectOne((r) => r.url === '/api/v1/products');
      expect(req.request.params.get('category')).toBe('brownies');
      expect(req.request.params.get('q')).toBe('fudge');
      expect(req.request.params.get('page')).toBe('1');
      expect(req.request.params.get('size')).toBe('12');
      req.flush({ content: [product()], page: 1, size: 12, totalElements: 13 });
      await fixture.whenStable();

      const root = fixture.nativeElement as HTMLElement;
      expect(root.textContent).toContain('Fudge Brownie Box');
      expect(root.textContent).toContain('From ৳1,250.00');
      expect(root.textContent).toContain('Page 2 of 2');
      expect(root.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('Brownies');
    });

    it('puts the search in the URL and starts from the first page', async () => {
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      const fixture = TestBed.createComponent(CatalogPage);
      fixture.componentRef.setInput('category', 'cookies');
      fixture.componentRef.setInput('page', '3');
      await settle();
      http.expectOne('/api/v1/categories').flush([]);
      http
        .expectOne((r) => r.url === '/api/v1/products')
        .flush({ content: [], page: 3, size: 12, totalElements: 0 });
      await fixture.whenStable();

      const root = fixture.nativeElement as HTMLElement;
      const input = root.querySelector<HTMLInputElement>('#catalog-search');
      if (!input) {
        throw new Error('no search box');
      }
      input.value = '  choc chip  ';
      input.dispatchEvent(new Event('input'));
      root.querySelector('form')?.dispatchEvent(new Event('submit'));

      expect(navigate).toHaveBeenCalledWith(['/'], {
        queryParams: { category: 'cookies', q: 'choc chip', page: undefined },
      });
      expect(root.textContent).toContain('No products match');
    });

    it('renders product names as text, never as HTML', async () => {
      const fixture = TestBed.createComponent(CatalogPage);
      await settle();
      http.expectOne('/api/v1/categories').flush([]);
      http
        .expectOne((r) => r.url === '/api/v1/products')
        .flush({
          content: [product({ name: '<img src=x onerror=alert(1)>' })],
          page: 0,
          size: 12,
          totalElements: 1,
        });
      await fixture.whenStable();

      const list = (fixture.nativeElement as HTMLElement).querySelector('ul');
      expect(list?.textContent).toContain('<img src=x onerror=alert(1)>');
      expect(list?.querySelector('img[src="x"]')).toBeNull();
    });
  });

  describe('ProductPage', () => {
    it('shows the offered customizations with their price deltas', async () => {
      const fixture = TestBed.createComponent(ProductPage);
      fixture.componentRef.setInput('slug', 'fudge-brownie-box');
      await settle();
      http.expectOne('/api/v1/products/fudge-brownie-box').flush(
        product({
          customizationGroups: [
            {
              id: 'g1',
              code: 'BOX_SIZE',
              name: 'Box size',
              selectionType: 'SINGLE',
              required: true,
              minSelections: 1,
              maxSelections: 1,
              options: [
                { id: 'o1', name: '6 pieces', priceDelta: 0, minLeadHours: 12 },
                { id: 'o2', name: '12 pieces', priceDelta: 600, minLeadHours: 72 },
              ],
            },
            {
              id: 'g2',
              code: 'GIFT_NOTE',
              name: 'Gift note',
              selectionType: 'TEXT',
              required: false,
              minSelections: 0,
              maxSelections: 1,
              textMaxLength: 50,
              options: [],
            },
          ],
        }),
      );
      await fixture.whenStable();

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Fudge Brownie Box');
      expect(text).toContain('Box size');
      expect(text).toContain('(required)');
      expect(text).toContain('Included');
      expect(text).toContain('+ ৳600.00');
      expect(text).toContain('up to 50 characters');
      // Only an option needing more notice than the product (24 h) says so.
      const notices = [
        ...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="option-notice"]'),
      ].map((n) => n.textContent?.trim());
      expect(notices).toEqual(["· needs 72 hours' notice"]);
    });

    it('lists combinations that cannot be chosen together', async () => {
      const fixture = TestBed.createComponent(ProductPage);
      fixture.componentRef.setInput('slug', 'celebration-cake');
      await settle();
      http.expectOne('/api/v1/products/celebration-cake').flush(
        product({
          customizationGroups: [
            {
              id: 'g1',
              code: 'FINISH',
              name: 'Finish',
              selectionType: 'SINGLE',
              required: true,
              minSelections: 1,
              maxSelections: 1,
              options: [
                { id: 'f1', name: 'Fondant', priceDelta: 500 },
                { id: 'f2', name: 'Buttercream', priceDelta: 0 },
              ],
            },
            {
              id: 'g2',
              code: 'TOPPING',
              name: 'Topping',
              selectionType: 'MULTIPLE',
              required: false,
              minSelections: 0,
              maxSelections: 2,
              options: [{ id: 't1', name: 'Fresh fruit', priceDelta: 300 }],
            },
          ],
          // The second pair names an option the page does not show (inactive): left out.
          disallowedCombinations: [{ optionIds: ['f1', 't1'] }, { optionIds: ['f2', 'gone'] }],
        }),
      );
      await fixture.whenStable();

      const note = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="clashes"]');
      expect(note?.textContent?.trim()).toBe('Not available together: Fondant + Fresh fruit.');
    });

    it('lets the customer choose a cake, guided by the same rules as the server', async () => {
      const fixture = TestBed.createComponent(ProductPage);
      fixture.componentRef.setInput('slug', 'celebration-cake');
      await settle();
      http.expectOne('/api/v1/products/celebration-cake').flush(
        product({
          name: 'Celebration Cake',
          customizationGroups: [
            {
              id: 'size',
              code: 'CAKE_SIZE',
              name: 'Size',
              selectionType: 'SINGLE',
              required: true,
              minSelections: 1,
              maxSelections: 1,
              options: [
                { id: 'half', name: '500 g', priceDelta: 0 },
                { id: 'two', name: '2 kg', priceDelta: 1800 },
              ],
            },
            {
              id: 'frosting',
              code: 'CAKE_FROSTING',
              name: 'Frosting',
              selectionType: 'SINGLE',
              required: false,
              minSelections: 1,
              maxSelections: 1,
              options: [{ id: 'fondant', name: 'Fondant', priceDelta: 500 }],
            },
            {
              id: 'toppings',
              code: 'CAKE_TOPPINGS',
              name: 'Toppings',
              selectionType: 'MULTIPLE',
              required: false,
              minSelections: 0,
              maxSelections: 1,
              options: [
                { id: 'berry', name: 'Strawberries', priceDelta: 150 },
                { id: 'nut', name: 'Walnuts', priceDelta: 80 },
              ],
            },
            {
              id: 'message',
              code: 'CAKE_MESSAGE',
              name: 'Message on the cake',
              selectionType: 'TEXT',
              required: false,
              minSelections: 0,
              maxSelections: 1,
              textMaxLength: 30,
              options: [],
            },
          ],
          disallowedCombinations: [{ optionIds: ['fondant', 'berry'] }],
        }),
      );
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      const input = (id: string) => {
        const el = root.querySelector<HTMLInputElement>(`input[value="${id}"]`);
        if (!el) {
          throw new Error(`no input for ${id}`);
        }
        return el;
      };
      const click = async (id: string) => {
        input(id).click();
        await fixture.whenStable();
      };
      const status = () =>
        root.querySelector('[data-testid="selection-status"]')?.textContent?.trim();

      // Groups in the order the server sent; nothing flagged before the customer starts.
      expect(
        [...root.querySelectorAll('fieldset')].map((f) => f.getAttribute('data-group')),
      ).toEqual(['CAKE_SIZE', 'CAKE_FROSTING', 'CAKE_TOPPINGS', 'CAKE_MESSAGE']);
      expect(input('half').type).toBe('radio');
      expect(input('berry').type).toBe('checkbox');
      expect(root.querySelector('[data-testid="group-problem"]')).toBeNull();
      expect(status()).toBe('Still to complete: Size.');

      await click('two');
      expect(status()).toBe('Your choices are complete. The price is confirmed at checkout.');

      const reasons = () =>
        [...root.querySelectorAll('[data-testid="option-unavailable"]')].map((n) =>
          n.textContent?.trim(),
        );
      // Fondant rules out strawberries.
      await click('fondant');
      expect(input('berry').disabled).toBe(true);
      expect(reasons()).toEqual(['(not with Fondant)']);
      // Back to no frosting, with the one topping allowed already taken.
      await click('nut');
      root.querySelector<HTMLInputElement>('input[name="group-frosting"]:not([value])')?.click();
      await fixture.whenStable();
      expect(input('fondant').checked).toBe(false);
      expect(input('berry').disabled).toBe(true);
      expect(reasons()).toEqual(['(up to 1)']);
      await click('nut');
      expect(input('berry').disabled).toBe(false);
      expect(reasons()).toEqual([]);

      // Markup in a message is just text: counted, never rendered.
      const textarea = root.querySelector<HTMLTextAreaElement>('textarea');
      if (!textarea) {
        throw new Error('no message box');
      }
      textarea.value = '<b onclick=x>Shubho</b>';
      textarea.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      expect(root.querySelector('[data-testid="text-count"]')?.textContent?.trim()).toBe('23 / 30');
      expect(root.querySelector('b')).toBeNull();
      textarea.value = 'x'.repeat(31);
      textarea.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      expect(root.querySelector('[data-testid="group-problem"]')?.textContent).toContain(
        'Keep the message to 30 characters.',
      );
      expect(textarea.getAttribute('aria-invalid')).toBe('true');
      expect(status()).toBe('Still to complete: Message on the cake.');
    });

    it('adds the complete choices to the cart with the chosen quantity', async () => {
      localStorage.clear();
      const cart = TestBed.inject(CartStore);
      cart.clear();
      const fixture = TestBed.createComponent(ProductPage);
      fixture.componentRef.setInput('slug', 'fudge-brownie-box');
      await settle();
      http.expectOne('/api/v1/products/fudge-brownie-box').flush(
        product({
          customizationGroups: [
            {
              id: 'g1',
              code: 'BOX_SIZE',
              name: 'Box size',
              selectionType: 'SINGLE',
              required: true,
              minSelections: 1,
              maxSelections: 1,
              options: [{ id: 'o2', name: '12 pieces', priceDelta: 600 }],
            },
          ],
        }),
      );
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      const add = [...root.querySelectorAll('button')].find((b) =>
        b.textContent?.includes('Add to cart'),
      );
      expect(add?.disabled).toBe(true);

      root.querySelector<HTMLInputElement>('input[value="o2"]')?.click();
      const quantity = root.querySelector<HTMLInputElement>('#quantity');
      if (!quantity || !add) {
        throw new Error('no cart controls');
      }
      quantity.value = '3';
      quantity.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      expect(add.disabled).toBe(false);
      add.click();
      await fixture.whenStable();

      expect(cart.items()).toEqual([
        expect.objectContaining({
          productName: 'Fudge Brownie Box',
          choices: ['Box size: 12 pieces'],
          line: { productId: 'p1', quantity: 3, selectedOptionIds: ['o2'], customTexts: {} },
        }),
      ]);
      expect(root.querySelector('[data-testid="added"]')?.textContent).toContain('View cart');
      cart.clear();
    });

    it('explains a missing product instead of showing an error', async () => {
      const fixture = TestBed.createComponent(ProductPage);
      fixture.componentRef.setInput('slug', 'gone');
      await settle();
      http
        .expectOne('/api/v1/products/gone')
        .flush(
          { title: 'Not Found', status: 404, code: 'PRODUCT_NOT_FOUND' },
          { status: 404, statusText: 'Not Found' },
        );
      await fixture.whenStable();

      const root = fixture.nativeElement as HTMLElement;
      expect(root.textContent).toContain('This product is not available');
      expect(root.querySelector('[role="alert"]')).toBeNull();
    });
  });
});
