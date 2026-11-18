import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { Product } from '../../core/api/generated/models';
import { formatBdt } from '../../shared/bdt.pipe';
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
