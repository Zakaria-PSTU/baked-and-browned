import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { userMessage } from '../../core/http/problem';
import { BD_MOBILE_PATTERN, normalizeBdMobile } from './phone';

/** Contract `RegisterRequest` limits; the server validates them again. */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 128;

function bdMobile(control: AbstractControl<string>): ValidationErrors | null {
  return control.value && !BD_MOBILE_PATTERN.test(normalizeBdMobile(control.value))
    ? { bdMobile: true }
    : null;
}

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const password = group.get('password')?.value as string | undefined;
  const confirm = group.get('confirmPassword')?.value as string | undefined;
  return confirm && password !== confirm ? { passwordsDiffer: true } : null;
}

@Component({
  selector: 'bb-register-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly passwordMin = PASSWORD_MIN;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = new FormGroup(
    {
      fullName: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.minLength(2), Validators.maxLength(150)],
      }),
      phone: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, bdMobile],
      }),
      email: new FormControl('', {
        nonNullable: true,
        validators: [Validators.email, Validators.maxLength(255)],
      }),
      password: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.minLength(PASSWORD_MIN),
          Validators.maxLength(PASSWORD_MAX),
        ],
      }),
      confirmPassword: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
    },
    { validators: passwordsMatch },
  );

  protected showError(name: keyof RegisterPage['form']['controls']): boolean {
    const control = this.form.controls[name];
    return control.invalid && control.touched;
  }

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .register({
        fullName: value.fullName.trim(),
        phone: normalizeBdMobile(value.phone),
        email: value.email.trim() || undefined,
        password: value.password,
      })
      .subscribe({
        next: () => void this.router.navigateByUrl('/account'),
        error: (err: unknown) => {
          this.error.set(userMessage(err, 'Could not create your account. Please try again.'));
          this.submitting.set(false);
        },
      });
  }
}
