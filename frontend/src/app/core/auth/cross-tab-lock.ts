/**
 * Runs `work` while holding a browser-wide Web Lock, so only one tab at a time does it.
 *
 * Refresh tokens rotate and a reused token revokes the whole session, so two tabs must never
 * refresh with the same cookie at once. Under the lock, a second tab's request is created only
 * after the first tab's response has updated the cookie jar, so it presents the new token.
 * Where Web Locks are unavailable (old browsers, test environments) the work runs unguarded.
 */
export function withCrossTabLock<T>(name: string, work: () => Promise<T>): Promise<T> {
  const locks = (globalThis.navigator as Navigator | undefined)?.locks;
  return locks ? locks.request(name, () => work()) : work();
}
