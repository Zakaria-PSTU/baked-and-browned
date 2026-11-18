import { HttpXsrfTokenExtractor, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { MockInstance } from 'vitest';
import { LoginPage } from './login-page';
import { RegisterPage } from './register-page';

const user = { id: 'u1', fullName: 'Nusrat', phone: '+8801712345678', role: 'CUSTOMER' as const };
const authResponse = { accessToken: 't1', tokenType: 'Bearer', expiresIn: 900, user };

function setValue(root: HTMLElement, id: string, value: string): void {
  const input = root.querySelector<HTMLInputElement>(`#${id}`);
  if (!input) {
    throw new Error(`No input #${id}`);
  }
  input.value = value;
  input.dispatchEvent(new Event('input'));
  input.dispatchEvent(new Event('blur'));
}

function submit(root: HTMLElement): void {
  root.querySelector('form')?.dispatchEvent(new Event('submit'));
}

describe('auth pages', () => {
  let http: HttpTestingController;
  let navigateByUrl: MockInstance<Router['navigateByUrl']>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => null } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  describe('LoginPage', () => {
    it('does not call the API while the form is invalid', async () => {
      const fixture = TestBed.createComponent(LoginPage);
      await fixture.whenStable();
      submit(fixture.nativeElement);
      await fixture.whenStable();

      http.expectNone('/api/v1/auth/login');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Enter your mobile number or email.',
      );
    });

    it('normalizes a local phone number and follows only a safe return URL', async () => {
      const fixture = TestBed.createComponent(LoginPage);
      fixture.componentRef.setInput('returnUrl', '//evil.example');
      await fixture.whenStable();
      setValue(fixture.nativeElement, 'identifier', '01712345678');
      setValue(fixture.nativeElement, 'password', 'correct horse battery');
      submit(fixture.nativeElement);

      const req = http.expectOne('/api/v1/auth/login');
      expect(req.request.body.identifier).toBe('+8801712345678');
      req.flush(authResponse);
      await fixture.whenStable();
      expect(navigateByUrl).toHaveBeenCalledWith('/account');
    });

    it('shows the server message for invalid credentials', async () => {
      const fixture = TestBed.createComponent(LoginPage);
      await fixture.whenStable();
      setValue(fixture.nativeElement, 'identifier', 'nusrat@example.com');
      setValue(fixture.nativeElement, 'password', 'wrong password');
      submit(fixture.nativeElement);

      http.expectOne('/api/v1/auth/login').flush(
        {
          title: 'Unauthorized',
          status: 401,
          code: 'INVALID_CREDENTIALS',
          detail: 'The phone, email or password is incorrect.',
        },
        { status: 401, statusText: 'Unauthorized' },
      );
      await fixture.whenStable();
      const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
      expect(alert?.textContent).toContain('The phone, email or password is incorrect.');
      expect(navigateByUrl).not.toHaveBeenCalled();
    });
  });

  describe('RegisterPage', () => {
    async function filled() {
      const fixture = TestBed.createComponent(RegisterPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      setValue(root, 'fullName', 'Nusrat Jahan');
      setValue(root, 'phone', '017 1234 5678');
      setValue(root, 'password', 'correct horse battery');
      setValue(root, 'confirmPassword', 'correct horse battery');
      return fixture;
    }

    it('blocks short passwords and mismatched confirmation client-side', async () => {
      const fixture = await filled();
      const root = fixture.nativeElement as HTMLElement;
      setValue(root, 'password', 'too short');
      setValue(root, 'confirmPassword', 'something else');
      submit(root);
      await fixture.whenStable();

      http.expectNone('/api/v1/auth/register');
      expect(root.textContent).toContain('The passwords do not match.');
    });

    it('registers with a normalized phone, then logs in', async () => {
      const fixture = await filled();
      submit(fixture.nativeElement);

      const req = http.expectOne('/api/v1/auth/register');
      expect(req.request.body).toEqual({
        fullName: 'Nusrat Jahan',
        phone: '+8801712345678',
        email: undefined,
        password: 'correct horse battery',
      });
      req.flush(user, { status: 201, statusText: 'Created' });
      http.expectOne('/api/v1/auth/login').flush(authResponse);
      await fixture.whenStable();
      expect(navigateByUrl).toHaveBeenCalledWith('/account');
    });

    it('shows a duplicate-account conflict from the server', async () => {
      const fixture = await filled();
      submit(fixture.nativeElement);

      http.expectOne('/api/v1/auth/register').flush(
        {
          title: 'Conflict',
          status: 409,
          code: 'ACCOUNT_EXISTS',
          detail: 'An account with this phone number or email already exists.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await fixture.whenStable();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'An account with this phone number or email already exists.',
      );
    });
  });
});
