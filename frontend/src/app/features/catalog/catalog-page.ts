import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { BdtPipe } from '../../shared/bdt.pipe';
import { CatalogApi, PAGE_SIZE } from './catalog-api';

/**
 * Product listing. Filter state lives in the URL (`?category=&q=&page=`), bound to inputs by the
 * router, so it survives reloads and back/forward and can be shared.
 */
@Component({
  selector: 'bb-catalog-page',
  imports: [RouterLink, ReactiveFormsModule, BdtPipe],
  templateUrl: './catalog-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CatalogPage {
  private readonly api = inject(CatalogApi);
  private readonly router = inject(Router);

  readonly category = input<string | undefined>();
  readonly q = input<string | undefined>();
  readonly page = input<string | undefined>();

  protected readonly pageIndex = computed(() => {
    const parsed = Number.parseInt(this.page() ?? '0', 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
  });

  protected readonly searchForm = new FormGroup({
    q: new FormControl('', { nonNullable: true }),
  });

  protected readonly categories = rxResource({ stream: () => this.api.categories() });

  protected readonly products = rxResource({
    params: () => ({ category: this.category(), q: this.q(), page: this.pageIndex() }),
    stream: ({ params }) => this.api.products(params),
  });

  protected readonly totalPages = computed(() => {
    const value = this.products.value();
    return value ? Math.max(1, Math.ceil(value.totalElements / PAGE_SIZE)) : 1;
  });

  constructor() {
    // Keep the search box in step with the URL (for example after back/forward).
    effect(() => this.searchForm.controls.q.setValue(this.q() ?? '', { emitEvent: false }));
  }

  protected submitSearch(): void {
    const q = this.searchForm.controls.q.value.trim().slice(0, 100);
    void this.navigate({ category: this.category(), q: q || undefined, page: undefined });
  }

  protected goToPage(page: number): void {
    void this.navigate({
      category: this.category(),
      q: this.q(),
      page: page > 0 ? String(page) : undefined,
    });
  }

  private navigate(queryParams: Record<string, string | undefined>): Promise<boolean> {
    return this.router.navigate(['/'], { queryParams });
  }
}
