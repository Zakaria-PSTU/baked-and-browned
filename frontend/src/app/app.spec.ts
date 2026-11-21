import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from './app';
import { AdminAccess } from './core/auth/admin-access';
import { AuthSession } from './core/auth/auth-session';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('creates the shell', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the brand name as escaped text in the header', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const header = (fixture.nativeElement as HTMLElement).querySelector('header');
    expect(header?.textContent).toContain('Baked & Browned');
  });

  it('hosts a router outlet for feature routes', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('main router-outlet'),
    ).not.toBeNull();
  });

  it('offers log in and registration when signed out', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const nav = (fixture.nativeElement as HTMLElement).querySelector('nav');
    expect(nav?.textContent).toContain('Log in');
    expect(nav?.textContent).toContain('Register');
  });

  it('shows the user name as text and a log out button when signed in', async () => {
    TestBed.inject(AuthSession).start('token', 900, {
      id: 'u1',
      fullName: '<img src=x onerror=alert(1)>',
      phone: '+8801712345678',
      role: 'CUSTOMER',
    });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const nav = (fixture.nativeElement as HTMLElement).querySelector('nav');
    expect(nav?.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(nav?.querySelector('img')).toBeNull();
    expect(nav?.textContent).toContain('Log out');
  });
});

@Component({
  template: `
    <p role="alert" data-testid="stale-error">
      We could not load the dashboard. Please try again in a moment.
    </p>
    <button type="button">Approve</button>
  `,
})
class AdminStubPage {}

@Component({ template: '<p>Shop</p>' })
class ShopStubPage {}

describe('App, after admin access is removed (F-2)', () => {
  const admin = { id: 'u1', fullName: 'Rafiq', phone: '+8801712345678', role: 'ADMIN' as const };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: 'admin', component: AdminStubPage },
          { path: '', component: ShopStubPage },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    TestBed.inject(AuthSession).start('token', 900, admin);
  });

  async function demotedOn(url: string) {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('nav a[href="/admin"]')).not.toBeNull();

    TestBed.inject(AdminAccess).confirmAfterForbidden().subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/me')
      .flush({ ...admin, role: 'CUSTOMER' });
    await fixture.whenStable();
    return root;
  }

  it('keeps the admin page visible but read-only, under an inline message, and still signed in', async () => {
    const root = await demotedOn('/admin');

    const alert = root.querySelector('[data-testid="admin-access-removed"]');
    expect(alert?.getAttribute('role')).toBe('alert');
    expect(alert?.textContent).toContain('Admin access removed');
    expect(alert?.textContent).toContain('You are still signed in.');
    const page = root.querySelector<HTMLElement>('[data-testid="page"]');
    expect(page?.inert).toBe(true);
    expect(page?.textContent).toContain('Approve');
    // Admin navigation is gone; the customer session is not.
    expect(root.querySelector('nav a[href="/admin"]')).toBeNull();
    expect(root.querySelector('nav')?.textContent).toContain('Log out');
    expect(TestBed.inject(AuthSession).isAuthenticated()).toBe(true);
  });

  it('replaces the page’s stale "try again" error with the admin access message', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/admin');
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const stale = () => root.querySelector<HTMLElement>('[data-testid="stale-error"]');
    // Before any demotion, a page's own error is shown as usual.
    expect(getComputedStyle(stale()!).display).not.toBe('none');

    TestBed.inject(AdminAccess).confirmAfterForbidden().subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/me')
      .flush({ ...admin, role: 'CUSTOMER' });
    await fixture.whenStable();

    // The stale error is hidden; only the shell's message, outside the page, is announced.
    expect(getComputedStyle(stale()!).display).toBe('none');
    const visibleAlerts = [...root.querySelectorAll<HTMLElement>('[role="alert"]')].filter(
      (el) => getComputedStyle(el).display !== 'none',
    );
    expect(visibleAlerts.map((el) => el.dataset['testid'])).toEqual(['admin-access-removed']);
    // Still read-only, still signed in.
    expect(root.querySelector<HTMLElement>('[data-testid="page"]')?.inert).toBe(true);
    expect(TestBed.inject(AuthSession).isAuthenticated()).toBe(true);
  });

  it('shows nothing extra outside admin pages', async () => {
    const root = await demotedOn('/');

    expect(root.querySelector('[data-testid="admin-access-removed"]')).toBeNull();
    expect(root.querySelector<HTMLElement>('[data-testid="page"]')?.inert).toBe(false);
    expect(root.querySelector('nav a[href="/admin"]')).toBeNull();
  });
});
