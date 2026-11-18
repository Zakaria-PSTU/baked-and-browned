import { BD_MOBILE_PATTERN, normalizeBdMobile } from './phone';

describe('normalizeBdMobile', () => {
  it.each([
    ['01712345678', '+8801712345678'],
    ['017 1234-5678', '+8801712345678'],
    ['8801912345678', '+8801912345678'],
    [' +8801312345678 ', '+8801312345678'],
  ])('normalizes %s', (input, expected) => {
    expect(normalizeBdMobile(input)).toBe(expected);
    expect(BD_MOBILE_PATTERN.test(normalizeBdMobile(input))).toBe(true);
  });

  it.each(['01212345678', '0171234567', '+880171234567', 'abc'])(
    'leaves %s invalid for validation to reject',
    (input) => {
      expect(BD_MOBILE_PATTERN.test(normalizeBdMobile(input))).toBe(false);
    },
  );
});
