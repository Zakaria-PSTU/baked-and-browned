import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import type { CartLine, CustomizationGroup } from '../../core/api/generated/models';
import { BdtPipe } from '../../shared/bdt.pipe';
import { CatalogApi } from './catalog-api';
import {
  EMPTY_SELECTION,
  type Selection,
  atLimit,
  blockedBy,
  choose,
  problems,
  setText,
  textLength,
  toCartLine,
} from './customization-selection';

/**
 * Product detail where the customer chooses the customizations it offers (a cake's size, flavor,
 * frosting, toppings and message). The choices become a cart line once the cart ships; prices and
 * the final validation always come from the server.
 */
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

  protected readonly selection = signal<Selection>(EMPTY_SELECTION);

  /** Groups the customer has interacted with; only their problems are shown inline. */
  private readonly touched = signal<ReadonlySet<string>>(new Set());

  /** What still stops the choices from being ordered, by group id. */
  private readonly allProblems = computed(() => {
    const p = this.product.value();
    return new Map((p ? problems(p, this.selection()) : []).map((x) => [x.groupId, x.message]));
  });

  /** Problems to show next to their group. */
  protected readonly problems = computed(() => {
    const touched = this.touched();
    return new Map([...this.allProblems()].filter(([groupId]) => touched.has(groupId)));
  });

  /** Names of groups still to complete, for the status line. */
  protected readonly missing = computed(() => {
    const p = this.product.value();
    const open = this.allProblems();
    return (p?.customizationGroups ?? [])
      .filter((g) => open.has(g.id))
      .map((g) => g.name || g.code);
  });

  /** The line the cart will send, once every group is satisfied. */
  protected readonly cartLine = computed<CartLine | undefined>(() => {
    const p = this.product.value();
    return p && this.allProblems().size === 0 ? toCartLine(p, this.selection(), 1) : undefined;
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

  constructor() {
    // A different product starts from a clean slate.
    effect(() => {
      this.slug();
      this.selection.set(EMPTY_SELECTION);
      this.touched.set(new Set());
    });
  }

  protected chosen(optionId: string): boolean {
    return this.selection().optionIds.has(optionId);
  }

  protected noneChosen(group: CustomizationGroup): boolean {
    return !group.options.some((o) => this.chosen(o.id));
  }

  /** Why an option cannot be picked right now, or undefined when it can. */
  protected unavailable(group: CustomizationGroup, optionId: string): string | undefined {
    const p = this.product.value();
    if (!p || this.chosen(optionId)) {
      return undefined;
    }
    const clash = blockedBy(p, this.selection(), optionId);
    if (clash) {
      return `not with ${clash}`;
    }
    return atLimit(group, this.selection()) ? `up to ${group.maxSelections}` : undefined;
  }

  protected pick(group: CustomizationGroup, optionId: string | null): void {
    this.selection.update((s) => choose(group, s, optionId));
    this.touch(group);
  }

  protected type(group: CustomizationGroup, event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    this.selection.update((s) => setText(s, group.id, value));
    this.touch(group);
  }

  private touch(group: CustomizationGroup): void {
    if (!this.touched().has(group.id)) {
      this.touched.update((t) => new Set(t).add(group.id));
    }
  }

  protected length(group: CustomizationGroup): number {
    return textLength(this.selection().texts.get(group.id) ?? '');
  }
}
