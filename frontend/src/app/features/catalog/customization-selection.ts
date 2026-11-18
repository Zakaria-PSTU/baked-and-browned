import type { CartLine, CustomizationGroup, Product } from '../../core/api/generated/models';

/**
 * A customer's choices for one product, before it goes in the cart. Immutable: every change
 * returns a new value, so it sits in a signal.
 *
 * These checks mirror the server's (business-rules, Customization) only to guide the customer;
 * the server validates and prices every cart line again and stays authoritative.
 */
export interface Selection {
  readonly optionIds: ReadonlySet<string>;
  /** Group id to the message as typed. */
  readonly texts: ReadonlyMap<string, string>;
}

export const EMPTY_SELECTION: Selection = { optionIds: new Set(), texts: new Map() };

/** What still stops the selection from being ordered, for one group. */
export interface Problem {
  readonly groupId: string;
  readonly message: string;
}

/** The message as the server stores it: NFC-normalized and trimmed; blank is no message. */
export function normalizeText(raw: string): string {
  return raw.normalize('NFC').trim();
}

/** Length as the server counts it: code points, so a Bangla letter or an emoji is one. */
export function textLength(raw: string): number {
  return [...normalizeText(raw)].length;
}

function label(group: CustomizationGroup): string {
  return group.name || group.code;
}

function chosenIn(group: CustomizationGroup, selection: Selection): number {
  return group.options.filter((o) => selection.optionIds.has(o.id)).length;
}

/**
 * Picks `optionId` in `group`. A SINGLE group replaces its choice; a MULTIPLE group
 * toggles it. `null` clears a SINGLE group ("None").
 */
export function choose(
  group: CustomizationGroup,
  selection: Selection,
  optionId: string | null,
): Selection {
  const ids = new Set(selection.optionIds);
  if (group.selectionType === 'SINGLE') {
    group.options.forEach((o) => ids.delete(o.id));
    if (optionId !== null) {
      ids.add(optionId);
    }
  } else if (optionId !== null) {
    if (ids.has(optionId)) {
      ids.delete(optionId);
    } else {
      ids.add(optionId);
    }
  }
  return { optionIds: ids, texts: selection.texts };
}

export function setText(selection: Selection, groupId: string, text: string): Selection {
  const texts = new Map(selection.texts);
  texts.set(groupId, text);
  return { optionIds: selection.optionIds, texts };
}

/**
 * The name of a chosen option that cannot go with `optionId` on this product, if any. A
 * SINGLE group's own current choice never blocks its siblings, since picking one replaces it.
 */
export function blockedBy(
  product: Product,
  selection: Selection,
  optionId: string,
): string | undefined {
  const sameGroup = new Set(
    product.customizationGroups
      .filter((g) => g.selectionType === 'SINGLE' && g.options.some((o) => o.id === optionId))
      .flatMap((g) => g.options.map((o) => o.id)),
  );
  for (const pair of product.disallowedCombinations) {
    const [a, b] = pair.optionIds;
    const other = a === optionId ? b : b === optionId ? a : undefined;
    if (other !== undefined && selection.optionIds.has(other) && !sameGroup.has(other)) {
      return product.customizationGroups.flatMap((g) => g.options).find((o) => o.id === other)
        ?.name;
    }
  }
  return undefined;
}

/** Whether a MULTIPLE group is full, so its unchosen options cannot be added. */
export function atLimit(group: CustomizationGroup, selection: Selection): boolean {
  return group.selectionType === 'MULTIPLE' && chosenIn(group, selection) >= group.maxSelections;
}

/** The server's effective selection rule: optional groups may also be left empty. */
function countAllowed(group: CustomizationGroup, count: number): boolean {
  const lower = Math.max(group.minSelections, 1);
  const inRange = count >= lower && count <= group.maxSelections;
  return group.required ? inRange : count === 0 || inRange;
}

/** Problems in display order; empty when the selection can go in the cart. */
export function problems(product: Product, selection: Selection): Problem[] {
  const found: Problem[] = [];
  for (const group of product.customizationGroups) {
    if (group.selectionType === 'TEXT') {
      const length = textLength(selection.texts.get(group.id) ?? '');
      const limit = group.textMaxLength ?? 250;
      if (length > limit) {
        found.push({ groupId: group.id, message: `Keep the message to ${limit} characters.` });
      } else if (group.required && length === 0) {
        found.push({ groupId: group.id, message: `Please add a message for "${label(group)}".` });
      }
      continue;
    }
    if (!countAllowed(group, chosenIn(group, selection))) {
      const lower = Math.max(group.minSelections, 1);
      const range =
        lower === group.maxSelections ? `${lower}` : `${lower} to ${group.maxSelections}`;
      found.push({
        groupId: group.id,
        message: `${group.required ? 'Choose' : 'Choose none or'} ${range} for "${label(group)}".`,
      });
    }
  }
  return found;
}

/**
 * The cart line the server expects: only options this product shows (so a stale id never leaks
 * in), in display order, and only messages that are not blank.
 */
export function toCartLine(product: Product, selection: Selection, quantity: number): CartLine {
  const customTexts: Record<string, string> = {};
  const selectedOptionIds: string[] = [];
  for (const group of product.customizationGroups) {
    if (group.selectionType === 'TEXT') {
      const text = normalizeText(selection.texts.get(group.id) ?? '');
      if (text) {
        customTexts[group.id] = text;
      }
    } else {
      group.options
        .filter((o) => selection.optionIds.has(o.id))
        .forEach((o) => selectedOptionIds.push(o.id));
    }
  }
  return { productId: product.id, quantity, selectedOptionIds, customTexts };
}
