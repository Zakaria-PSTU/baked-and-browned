import { HttpErrorResponse } from '@angular/common/http';
import { keepaliveRefreshTransport } from './refresh-transport';

const body = {
  accessToken: 't2',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 'u1', fullName: 'Nusrat', phone: '+8801712345678', role: 'CUSTOMER' },
};

const json = (status: number, payload: unknown) =>
  new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

describe('keepaliveRefreshTransport', () => {
  it('sends a same-origin keepalive POST with the CSRF header', async () => {
    const fetchFn = vi.fn<typeof fetch>().mockResolvedValue(json(200, body));

    await expect(keepaliveRefreshTransport('/api/v1', fetchFn)('csrf-1')).resolves.toEqual(body);

    expect(fetchFn).toHaveBeenCalledWith('/api/v1/auth/refresh', {
      method: 'POST',
      keepalive: true,
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-CSRF-Token': 'csrf-1' },
    });
  });

  it('turns an error status into an HttpErrorResponse with that status', async () => {
    const problem = { title: 'Unauthorized', status: 401, code: 'INVALID_REFRESH_TOKEN' };
    const fetchFn = vi.fn<typeof fetch>().mockResolvedValue(json(401, problem));

    const error: unknown = await keepaliveRefreshTransport(
      '/api/v1',
      fetchFn,
    )('csrf-1').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect((error as HttpErrorResponse).status).toBe(401);
    expect((error as HttpErrorResponse).error).toEqual(problem);
  });

  it('turns a network failure into status 0', async () => {
    const fetchFn = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'));

    const error: unknown = await keepaliveRefreshTransport(
      '/api/v1',
      fetchFn,
    )('csrf-1').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect((error as HttpErrorResponse).status).toBe(0);
  });
});
