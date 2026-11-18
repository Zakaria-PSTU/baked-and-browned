import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder landing page; the catalog arrives in Phase 2. */
@Component({
  selector: 'bb-home-page',
  template: `
    <section class="py-12 text-center">
      <h1 class="text-3xl font-semibold tracking-tight">Fresh from our Dhaka kitchen</h1>
      <p class="mt-3 text-stone-600">
        Brownie boxes, cookies, pastries and custom cakes — coming soon.
      </p>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomePage {}
