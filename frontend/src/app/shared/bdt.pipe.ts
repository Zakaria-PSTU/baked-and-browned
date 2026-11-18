import { Pipe, PipeTransform } from '@angular/core';

/** South Asian digit grouping (1,25,000.00), as used in Bangladesh, always two decimals. */
const FORMAT = new Intl.NumberFormat('en-IN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** `৳1,250.00`. Display only: amounts always come from the server and are never recomputed. */
export function formatBdt(amount: number): string {
  return `৳${FORMAT.format(amount)}`;
}

@Pipe({ name: 'bdt' })
export class BdtPipe implements PipeTransform {
  transform(amount: number | null | undefined): string {
    return amount == null ? '' : formatBdt(amount);
  }
}
