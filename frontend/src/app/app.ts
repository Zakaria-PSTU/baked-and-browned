import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AdminAccess } from './core/auth/admin-access';
import { AuthService } from './core/auth/auth.service';
import { AuthSession } from './core/auth/auth-session';
import { CartStore } from './features/cart/cart-store';

@Component({
  selector: 'bb-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly brandName = 'Baked & Browned';
  private readonly session = inject(AuthSession);

  protected readonly user = this.session.user;
  protected readonly isAdmin = this.session.isAdmin;
  protected readonly cartCount = inject(CartStore).count;

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /**
   * F-2: the admin page on screen was opened before this user's admin access was removed. It stays
   * visible but read-only, under an inline message, until the user leaves it.
   */
  private readonly adminAccess = inject(AdminAccess);
  protected readonly adminAccessRemoved = computed(
    () => this.adminAccess.removed() && this.url().startsWith('/admin'),
  );

  protected logout(): void {
    // The local session is cleared even if the request fails; go home either way.
    this.auth.logout().subscribe({ complete: () => void this.router.navigateByUrl('/') });
  }
}
