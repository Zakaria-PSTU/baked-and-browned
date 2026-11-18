import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
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
