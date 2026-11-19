import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

/** True for an absolute http(s) URL; anything else (for example `javascript:`) is refused. */
export function isWebUrl(url: string): boolean {
  try {
    const { protocol } = new URL(url);
    return protocol === 'https:' || protocol === 'http:';
  } catch {
    return false;
  }
}

/** Leaves the app for another site, such as a hosted payment page. Replaceable in tests. */
@Injectable({ providedIn: 'root' })
export class ExternalNavigation {
  private readonly document = inject(DOCUMENT);

  /** @throws Error when `url` is not an absolute http(s) URL */
  go(url: string): void {
    if (!isWebUrl(url)) {
      throw new Error('Refusing to navigate to a non-web URL');
    }
    this.document.location.assign(url);
  }
}
