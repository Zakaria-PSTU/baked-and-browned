import type { CustomizationGroup, Product } from '../../core/api/generated/models';
import {
  EMPTY_SELECTION,
  type Selection,
  atLimit,
  blockedBy,
  choose,
  problems,
  setText,
  textLength,
  toCartLine,
} from './customization-selection';

const size: CustomizationGroup = {
  id: 'size',
  code: 'CAKE_SIZE',
  name: 'Size',
  selectionType: 'SINGLE',
  required: true,
  minSelections: 1,
  maxSelections: 1,
  options: [
    { id: 'half', name: '500 g', priceDelta: 0 },
    { id: 'two', name: '2 kg', priceDelta: 1800 },
  ],
};
const frosting: CustomizationGroup = {
  id: 'frosting',
  code: 'CAKE_FROSTING',
  name: 'Frosting',
  selectionType: 'SINGLE',
  required: false,
  minSelections: 1,
  maxSelections: 1,
  options: [
    { id: 'butter', name: 'Buttercream', priceDelta: 0 },
    { id: 'fondant', name: 'Fondant', priceDelta: 500 },
  ],
};
const toppings: CustomizationGroup = {
  id: 'toppings',
  code: 'CAKE_TOPPINGS',
  name: 'Toppings',
  selectionType: 'MULTIPLE',
  required: false,
  minSelections: 0,
  maxSelections: 2,
  options: [
    { id: 'berry', name: 'Strawberries', priceDelta: 150 },
    { id: 'nut', name: 'Walnuts', priceDelta: 80 },
    { id: 'gold', name: 'Gold leaf', priceDelta: 600 },
  ],
};
const message: CustomizationGroup = {
  id: 'message',
  code: 'CAKE_MESSAGE',
  name: 'Message',
  selectionType: 'TEXT',
  required: false,
  minSelections: 0,
  maxSelections: 1,
  textMaxLength: 5,
  options: [],
};
const cake: Product = {
  id: 'cake',
  name: 'Celebration Cake',
  slug: 'celebration-cake',
  categoryId: 'c',
  basePrice: 2200,
  currency: 'BDT',
  minLeadHours: 24,
  customizationGroups: [size, frosting, toppings, message],
  disallowedCombinations: [{ optionIds: ['berry', 'fondant'] }],
};

const pick = (s: Selection, ...steps: [CustomizationGroup, string | null][]) =>
  steps.reduce((acc, [g, id]) => choose(g, acc, id), s);

describe('customization selection', () => {
  it('replaces a single choice, clears it with None and toggles multiple ones', () => {
    let s = pick(EMPTY_SELECTION, [size, 'half'], [size, 'two']);
    expect([...s.optionIds]).toEqual(['two']);
    s = pick(s, [frosting, 'butter'], [frosting, null]);
    expect([...s.optionIds]).toEqual(['two']);
    s = pick(s, [toppings, 'berry'], [toppings, 'nut'], [toppings, 'berry']);
    expect([...s.optionIds].sort()).toEqual(['nut', 'two']);
  });

  it('applies the effective selection rule per group', () => {
    expect(problems(cake, EMPTY_SELECTION)).toEqual([
      { groupId: 'size', message: 'Choose 1 for "Size".' },
    ]);
    const s = pick(EMPTY_SELECTION, [size, 'half']);
    expect(problems(cake, s)).toEqual([]);
    // Choosing past a group's maximum is reported (the page also disables it).
    const many = pick(s, [toppings, 'berry'], [toppings, 'nut'], [toppings, 'gold']);
    expect(problems(cake, many)).toEqual([
      { groupId: 'toppings', message: 'Choose none or 1 to 2 for "Toppings".' },
    ]);
    expect(atLimit(toppings, pick(s, [toppings, 'berry']))).toBe(false);
    expect(atLimit(toppings, pick(s, [toppings, 'berry'], [toppings, 'nut']))).toBe(true);
    expect(atLimit(size, s)).toBe(false);
  });

  it('names the chosen option that blocks a disallowed one, either way round', () => {
    const withBerries = pick(EMPTY_SELECTION, [toppings, 'berry']);
    expect(blockedBy(cake, withBerries, 'fondant')).toBe('Strawberries');
    expect(blockedBy(cake, withBerries, 'butter')).toBeUndefined();
    const withFondant = pick(EMPTY_SELECTION, [frosting, 'fondant']);
    expect(blockedBy(cake, withFondant, 'berry')).toBe('Fondant');
    expect(blockedBy(cake, withFondant, 'nut')).toBeUndefined();
  });

  it('counts message length in code points after trimming, like the server', () => {
    expect(textLength('  শুভ  ')).toBe(3);
    expect(textLength('🎂🎂')).toBe(2);
    // NFC: "e" + combining acute is one character.
    expect(textLength('é')).toBe(1);
    const tooLong = setText(pick(EMPTY_SELECTION, [size, 'half']), 'message', '123456');
    expect(problems(cake, tooLong)).toEqual([
      { groupId: 'message', message: 'Keep the message to 5 characters.' },
    ]);
    const required = { ...cake, customizationGroups: [{ ...message, required: true }] };
    expect(problems(required, setText(EMPTY_SELECTION, 'message', '   '))).toEqual([
      { groupId: 'message', message: 'Please add a message for "Message".' },
    ]);
  });

  it('builds the cart line in display order, without blank messages or unknown options', () => {
    let s = pick(EMPTY_SELECTION, [toppings, 'nut'], [toppings, 'berry'], [size, 'two']);
    s = { ...s, optionIds: new Set([...s.optionIds, 'stale']) };
    expect(toCartLine(cake, setText(s, 'message', '  <b>Hi</b> '), 2)).toEqual({
      productId: 'cake',
      quantity: 2,
      selectedOptionIds: ['two', 'berry', 'nut'],
      customTexts: { message: '<b>Hi</b>' },
    });
    expect(toCartLine(cake, setText(s, 'message', '  '), 1).customTexts).toEqual({});
  });
});
