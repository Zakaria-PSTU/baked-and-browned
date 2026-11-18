import { TestBed } from '@angular/core/testing';
import { AuthSession } from './auth-session';

const user = { id: 'u1', fullName: 'Nusrat', phone: '+8801712345678', role: 'CUSTOMER' as const };

describe('AuthSession', () => {
  let session: AuthSession;

  beforeEach(() => {
    session = TestBed.inject(AuthSession);
  });

  it('starts signed out', () => {
    expect(session.isAuthenticated()).toBe(false);
    expect(session.accessToken()).toBeNull();
    expect(session.needsRefresh()).toBe(false);
  });

  it('holds the token in memory and derives roles', () => {
    session.start('t1', 900, { ...user, role: 'ADMIN' });
    expect(session.accessToken()).toBe('t1');
    expect(session.isAdmin()).toBe(true);
    session.clear();
    expect(session.isAuthenticated()).toBe(false);
  });

  it('asks for a refresh 30 s before the server-side expiry', () => {
    session.start('t1', 900, user, 0);
    expect(session.needsRefresh(869_999)).toBe(false);
    expect(session.needsRefresh(870_000)).toBe(true);
  });

  it('never persists the token to web storage', () => {
    session.start('secret-token', 900, user);
    expect(JSON.stringify({ ...localStorage })).not.toContain('secret-token');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('secret-token');
  });
});
