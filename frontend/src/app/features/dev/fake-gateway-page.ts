import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { userMessage } from '../../core/http/problem';
import { BdtPipe } from '../../shared/bdt.pipe';
import { ExternalNavigation } from '../../shared/external-navigation';
import { FakeGatewayApi, type FakeOutcome } from './fake-gateway-api';

/**
 * Development only: the fake gateway's "hosted page" (`/dev/fake-gateway?session=…`), where the
 * developer plays the customer on the provider's site. Finishing records the outcome provider side;
 * the shop learns of it only through the verified callback (or, if it is lost, reconciliation).
 * The browser then goes to the session's return URL, as with a real provider.
 */
@Component({
  selector: 'bb-fake-gateway-page',
  imports: [BdtPipe],
  templateUrl: './fake-gateway-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FakeGatewayPage {
  private readonly api = inject(FakeGatewayApi);
  private readonly external = inject(ExternalNavigation);

  /** Query parameter `session`. */
  readonly session = input<string>();

  protected readonly payment = rxResource({
    params: () => this.session(),
    stream: ({ params }) => this.api.session(params),
  });
  /** Off: the provider's callback is lost, as when a webhook never arrives. */
  protected readonly notifyShop = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected toggleNotify(event: Event): void {
    this.notifyShop.set((event.target as HTMLInputElement).checked);
  }

  protected finish(outcome: FakeOutcome): void {
    const session = this.session();
    if (!session || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .finish(session, outcome, this.notifyShop())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (finished) => this.back(finished.returnUrl),
        error: (err: unknown) =>
          this.error.set(userMessage(err, 'The test payment could not be finished.')),
      });
  }

  protected back(returnUrl: string): void {
    try {
      this.external.go(returnUrl);
    } catch {
      this.error.set('The return address of this test payment is not a web address.');
    }
  }
}
