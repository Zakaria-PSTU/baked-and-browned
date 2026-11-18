/**
 * Returns `candidate` only if it is an in-app path, otherwise `fallback`. Prevents open redirects
 * through `?returnUrl=`: absolute URLs, protocol-relative `//host`, backslash tricks (`/\host`) and
 * scheme URLs such as `javascript:` are all rejected.
 */
export function safeReturnUrl(candidate: string | null | undefined, fallback = '/'): string {
  if (
    !candidate ||
    !candidate.startsWith('/') ||
    candidate.startsWith('//') ||
    candidate.includes('\\')
  ) {
    return fallback;
  }
  try {
    // Resolving against a dummy origin must not change the origin.
    const resolved = new URL(candidate, 'https://app.invalid');
    if (resolved.origin !== 'https://app.invalid') {
      return fallback;
    }
    return resolved.pathname + resolved.search + resolved.hash;
  } catch {
    return fallback;
  }
}
