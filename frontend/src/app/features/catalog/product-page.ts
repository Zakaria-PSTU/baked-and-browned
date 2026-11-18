import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { BdtPipe } from '../../shared/bdt.pipe';
import { CatalogApi } from './catalog-api';

/** Product detail with the customizations it offers. Ordering arrives with the cart (Phase 2). */
@Component({
  selector: 'bb-product-page',
  imports: [RouterLink, BdtPipe],
  templateUrl: './product-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductPage {
  private readonly api = inject(CatalogApi);

  /** Route parameter `:slug`. */
  readonly slug = input.required<string>();

  protected readonly product = rxResource({
    params: () => this.slug(),
    stream: ({ params }) => this.api.product(params),
  });

  /** Disallowed combinations as option-name pairs, for a short note under the options. */
  protected readonly clashes = computed(() => {
    const p = this.product.value();
    if (!p) {
      return [];
    }
    const names = new Map<string, string>();
    p.customizationGroups.forEach((g) => g.options.forEach((o) => names.set(o.id, o.name)));
    return p.disallowedCombinations
      .map((c) => c.optionIds.map((id) => names.get(id)))
      .filter((pair): pair is string[] => pair.every((name) => name !== undefined))
      .map((pair) => pair.join(' + '));
  });

  protected readonly notFound = computed(() => {
    const error = this.product.error();
    const cause = error && 'cause' in error ? error.cause : error;
    return cause instanceof HttpErrorResponse && cause.status === 404;
  });
}
