import { safeReturnUrl } from './safe-return-url';

describe('safeReturnUrl', () => {
  it('keeps in-app paths with query and hash', () => {
    expect(safeReturnUrl('/account')).toBe('/account');
    expect(safeReturnUrl('/orders/42?tab=items#top')).toBe('/orders/42?tab=items#top');
  });

  it.each([
    'https://evil.example/phish',
    '//evil.example',
    '/\\evil.example',
    '\\\\evil.example',
    'javascript:alert(1)',
    'account',
    '',
    null,
    undefined,
  ])('rejects %s', (candidate) => {
    expect(safeReturnUrl(candidate, '/home')).toBe('/home');
  });
});
