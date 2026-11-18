import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../core/api/generated/api-configuration';
import { getProduct, listCategories, listProducts } from '../../core/api/generated/functions';
import type { Category, Product, ProductPage } from '../../core/api/generated/models';

export const PAGE_SIZE = 12;

/** Public catalog reads (contract tag Catalog), returning response bodies. */
@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(ApiConfiguration).rootUrl;

  categories(): Observable<Category[]> {
    return listCategories(this.http, this.rootUrl).pipe(map((r) => r.body));
  }

  products(query: { category?: string; q?: string; page: number }): Observable<ProductPage> {
    return listProducts(this.http, this.rootUrl, {
      category: query.category || undefined,
      q: query.q || undefined,
      page: query.page,
      size: PAGE_SIZE,
    }).pipe(map((r) => r.body));
  }

  product(slug: string): Observable<Product> {
    return getProduct(this.http, this.rootUrl, { slug }).pipe(map((r) => r.body));
  }
}
