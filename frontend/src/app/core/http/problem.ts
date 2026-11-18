import { HttpErrorResponse } from '@angular/common/http';

/** The API's application/problem+json body (contract `Problem`). */
export interface Problem {
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly code?: string;
  readonly traceId?: string;
}

function isProblem(body: unknown): body is Problem {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as Problem).status === 'number' &&
    typeof (body as Problem).title === 'string'
  );
}

/** The problem body of a failed API call, or null for network errors and non-problem responses. */
export function problemOf(error: unknown): Problem | null {
  return error instanceof HttpErrorResponse && isProblem(error.error) ? error.error : null;
}

/**
 * A message safe to show the user. Server `detail` texts are written for customers and contain no
 * internal data; Angular renders them as text, never HTML.
 */
export function userMessage(
  error: unknown,
  fallback = 'Something went wrong. Please try again.',
): string {
  if (error instanceof HttpErrorResponse && error.status === 0) {
    return 'Cannot reach Baked & Browned right now. Check your connection and try again.';
  }
  const problem = problemOf(error);
  if (problem?.code === 'RATE_LIMITED') {
    return 'Too many attempts. Please wait a few minutes and try again.';
  }
  return problem?.detail ?? fallback;
}
