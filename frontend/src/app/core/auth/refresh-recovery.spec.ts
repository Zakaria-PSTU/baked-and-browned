import { TestBed } from '@angular/core/testing';
import { IN_FLIGHT_KEY, RECOVERY_BUDGET_MS, RefreshRecovery } from './refresh-recovery';

describe('RefreshRecovery', () => {
  let recovery: RefreshRecovery;
  let xsrf: string | null;
  const readXsrf = () => xsrf;

  /** Resolves `true` if `promise` settled within `ms` of fake time. */
  async function settlesWithin(promise: Promise<void>, ms: number): Promise<boolean> {
    let settled = false;
    void promise.then(() => (settled = true));
    await vi.advanceTimersByTimeAsync(ms);
    return settled;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-20T06:00:00Z'));
    localStorage.clear();
    xsrf = 'csrf-1';
    recovery = TestBed.inject(RefreshRecovery);
  });

  afterEach(() => vi.useRealTimers());

  it('does not wait without a marker', async () => {
    expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), 0)).toBe(true);
  });

  it('writes and clears the marker', () => {
    recovery.markInFlight('csrf-1');
    expect(JSON.parse(localStorage.getItem(IN_FLIGHT_KEY) ?? 'null')).toEqual({
      ts: Date.now(),
      xsrf: 'csrf-1',
    });
    recovery.clearInFlight();
    expect(localStorage.getItem(IN_FLIGHT_KEY)).toBeNull();
  });

  it('waits until the CSRF cookie changes, then stops', async () => {
    recovery.markInFlight('csrf-1');
    const waiting = recovery.awaitInterruptedRefresh(readXsrf);

    expect(await settlesWithin(waiting, 1000)).toBe(false);
    xsrf = 'csrf-2';
    expect(await settlesWithin(waiting, 100)).toBe(true);
    expect(localStorage.getItem(IN_FLIGHT_KEY)).toBeNull();
  });

  it('keeps the marker while waiting, for a next page if this one is unloaded too', async () => {
    recovery.markInFlight('csrf-1');
    const waiting = recovery.awaitInterruptedRefresh(readXsrf);

    expect(await settlesWithin(waiting, 500)).toBe(false);
    expect(localStorage.getItem(IN_FLIGHT_KEY)).not.toBeNull();
    // A "next page" started now waits for the same rotation, within the same budget.
    const nextPage = TestBed.inject(RefreshRecovery).awaitInterruptedRefresh(readXsrf);
    expect(await settlesWithin(nextPage, 500)).toBe(false);
    xsrf = 'csrf-2';
    expect(await settlesWithin(Promise.all([waiting, nextPage]).then(() => undefined), 100)).toBe(true);
  });

  it('gives up when the budget runs out', async () => {
    recovery.markInFlight('csrf-1');
    const waiting = recovery.awaitInterruptedRefresh(readXsrf);

    expect(await settlesWithin(waiting, RECOVERY_BUDGET_MS - 200)).toBe(false);
    expect(await settlesWithin(waiting, 200)).toBe(true);
    expect(localStorage.getItem(IN_FLIGHT_KEY)).toBeNull();
  });

  it('counts the budget from when the refresh was sent', async () => {
    recovery.markInFlight('csrf-1', Date.now() - 4000);
    expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), 1000)).toBe(true);
  });

  it('ignores a stale marker without waiting', async () => {
    recovery.markInFlight('csrf-1', Date.now() - 60_000);
    expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), 0)).toBe(true);
    expect(localStorage.getItem(IN_FLIGHT_KEY)).toBeNull();
  });

  it('does not wait when the cookie already changed', async () => {
    recovery.markInFlight('csrf-1');
    xsrf = 'csrf-2';
    expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), 0)).toBe(true);
  });

  it('waits at most one budget for a marker from the future', async () => {
    recovery.markInFlight('csrf-1', Date.now() + 3_600_000);
    expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), RECOVERY_BUDGET_MS)).toBe(true);
  });

  it('discards a malformed marker', async () => {
    localStorage.setItem(IN_FLIGHT_KEY, '{"ts":"soon"}');
    expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), 0)).toBe(true);
    expect(localStorage.getItem(IN_FLIGHT_KEY)).toBeNull();
  });

  it('never throws when storage is unavailable', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    try {
      expect(() => recovery.markInFlight('csrf-1')).not.toThrow();
      expect(() => recovery.clearInFlight()).not.toThrow();
      expect(await settlesWithin(recovery.awaitInterruptedRefresh(readXsrf), 0)).toBe(true);
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });
});
