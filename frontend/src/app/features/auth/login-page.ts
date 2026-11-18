import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { safeReturnUrl } from '../../core/auth/safe-return-url';
import { userMessage } from '../../core/http/problem';
import { normalizeBdMobile } from './phone';

@Component({
  selector: 'bb-login-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** `?returnUrl=` (bound by the router); only in-app paths are followed. */
  readonly returnUrl = input<string | undefined>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = new FormGroup({
    identifier: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)],
    }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const { identifier, password } = this.form.getRawValue();
    const normalized = identifier.includes('@') ? identifier.trim() : normalizeBdMobile(identifier);
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login(normalized, password).subscribe({
      next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl(), '/account')),
      error: (err: unknown) => {
        this.error.set(userMessage(err, 'Could not log in. Please try again.'));
        this.submitting.set(false);
      },
    });
  }
}
