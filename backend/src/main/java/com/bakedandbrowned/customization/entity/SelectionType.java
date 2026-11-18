package com.bakedandbrowned.customization.entity;

/** {@code customization_groups.selection_type} (VARCHAR with a CHECK constraint, not a native enum). */
public enum SelectionType {

	/** Exactly one option at most. */
	SINGLE,

	/** Several options, within the group's min/max. */
	MULTIPLE,

	/** One free-text message instead of options. */
	TEXT

}
