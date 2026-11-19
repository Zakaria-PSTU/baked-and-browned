import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { AuthSession } from '../../core/auth/auth-session';
import { userMessage } from '../../core/http/problem';

/** Minimal account page: proves the bearer + refresh path end to end via `GET /auth/me`. */
@Component({
  selector: 'bb-account-page',
  imports: [RouterLink],
  templateUrl: './account-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountPage implements OnInit {
  private readonly auth = inject(AuthService);
  protected readonly user = inject(AuthSession).user;
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.auth.reloadCurrentUser().subscribe({
      error: (err: unknown) => this.error.set(userMessage(err, 'Could not load your account.')),
    });
  }
}
