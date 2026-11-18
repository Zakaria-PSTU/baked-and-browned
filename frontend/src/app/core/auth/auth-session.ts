import { Injectable, computed, signal } from '@angular/core';
import type { User } from '../api/generated/models';

/** The signed-in user (contract `User`, generated from the OpenAPI document). */
export type SessionUser = User;

interface ActiveSession {
  readonly accessToken: string;
  /** Epoch milliseconds after which the access token should be treated as expired. */
  readonly expiresAt: number;
  readonly user: SessionUser;
}

/** Refresh this long before the server-side expiry, to absorb clock skew and request latency. */
const EXPIRY_MARGIN_MS = 30_000;

/**
 * In-memory session state. The access token lives only here: never in localStorage,
 * sessionStorage or a readable cookie, so injected script cannot read it after a reload.
 * The refresh token is an HttpOnly cookie the browser handles; this class never sees it.
 */
@Injectable({ providedIn: 'root' })
export class AuthSession {
  private readonly state = signal<ActiveSession | null>(null);

  readonly user = computed(() => this.state()?.user ?? null);
  readonly isAuthenticated = computed(() => this.state() !== null);
  readonly isAdmin = computed(() => this.state()?.user.role === 'ADMIN');

  /** Starts or replaces the session from a login/refresh response (`expiresIn` in seconds). */
  start(accessToken: string, expiresIn: number, user: SessionUser, now = Date.now()): void {
    this.state.set({ accessToken, expiresAt: now + expiresIn * 1000 - EXPIRY_MARGIN_MS, user });
  }

  /** Replaces the user details (for example after `GET /auth/me`), keeping the token. */
  updateUser(user: SessionUser): void {
    this.state.update((session) => (session ? { ...session, user } : session));
  }

  clear(): void {
    this.state.set(null);
  }

  accessToken(): string | null {
    return this.state()?.accessToken ?? null;
  }

  /** True when there is a token but it is (about to be) expired, so it should be refreshed first. */
  needsRefresh(now = Date.now()): boolean {
    const session = this.state();
    return session !== null && now >= session.expiresAt;
  }
}
