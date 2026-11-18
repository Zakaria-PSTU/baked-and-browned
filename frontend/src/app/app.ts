import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { AuthSession } from './core/auth/auth-session';

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

  protected logout(): void {
    // The local session is cleared even if the request fails; go home either way.
    this.auth.logout().subscribe({ complete: () => void this.router.navigateByUrl('/') });
  }
}
