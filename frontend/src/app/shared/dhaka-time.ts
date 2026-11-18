/** The bakery's time zone (business rules): dates and times are always shown in it. */
export const DHAKA = 'Asia/Dhaka';

const ISO_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: DHAKA,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const DAY = new Intl.DateTimeFormat('en-GB', {
  timeZone: DHAKA,
  weekday: 'short',
  day: 'numeric',
  month: 'short',
});
const TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: DHAKA,
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
});

/** The Asia/Dhaka calendar date of `instant`, as `YYYY-MM-DD`. */
export function dhakaDate(instant: Date): string {
  return ISO_DATE.format(instant);
}

/** The next `count` Dhaka calendar dates starting today. Dhaka has no daylight saving. */
export function nextDhakaDates(now: Date, count: number): string[] {
  return Array.from({ length: count }, (_, i) =>
    dhakaDate(new Date(now.getTime() + i * 86_400_000)),
  );
}

/** "Thu, 20 Nov" for a `YYYY-MM-DD` Dhaka date. */
export function formatDhakaDay(date: string): string {
  // Noon Dhaka time is the same calendar day everywhere it is formatted.
  return day(new Date(`${date}T12:00:00+06:00`));
}

/** "Thu, 20 Nov, 4:00 pm – 6:00 pm" for a slot's start and end instants. */
export function formatDhakaRange(startAt: string, endAt: string): string {
  const start = new Date(startAt);
  return `${day(start)}, ${TIME.format(start)} – ${TIME.format(new Date(endAt))}`;
}

/** "Thu, 20 Nov", assembled from parts so it does not depend on the locale's punctuation. */
function day(instant: Date): string {
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    DAY.formatToParts(instant).find((p) => p.type === type)?.value ?? '';
  return `${part('weekday')}, ${part('day')} ${part('month')}`;
}

/** "Wed, 18 Nov, 2:05 pm" for an instant, in Dhaka. */
export function formatDhakaDateTime(instant: string): string {
  const at = new Date(instant);
  return `${day(at)}, ${TIME.format(at)}`;
}
