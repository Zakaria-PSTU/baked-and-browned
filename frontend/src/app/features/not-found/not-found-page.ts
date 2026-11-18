import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'bb-not-found-page',
  imports: [RouterLink],
  template: `
    <section class="py-12 text-center">
      <h1 class="text-2xl font-semibold tracking-tight">Page not found</h1>
      <p class="mt-3 text-stone-600">
        <a routerLink="/" class="font-medium text-amber-800 underline">Back to the home page</a>
      </p>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundPage {}
