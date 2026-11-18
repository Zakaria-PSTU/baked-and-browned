/** The API's phone format (contract and database): `+8801[3-9]` followed by 8 digits. */
export const BD_MOBILE_PATTERN = /^\+8801[3-9][0-9]{8}$/;

/**
 * Convenience for the common local spelling: `01712345678` (with optional spaces or dashes)
 * becomes `+8801712345678`. Anything else is returned trimmed and left for validation to reject;
 * the server never accepts other spellings either.
 */
export function normalizeBdMobile(input: string): string {
  const compact = input.trim().replace(/[\s-]/g, '');
  if (/^01[3-9][0-9]{8}$/.test(compact)) {
    return '+88' + compact;
  }
  if (/^8801[3-9][0-9]{8}$/.test(compact)) {
    return '+' + compact;
  }
  return compact;
}
