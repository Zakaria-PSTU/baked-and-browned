package com.bakedandbrowned.identity.entity;

/** Stored in {@code refresh_tokens.revoke_reason} (VARCHAR(40)). */
public enum RevokeReason {

	/** The customer logged out. */
	LOGOUT,

	/** An already-rotated token was presented again: the family is assumed stolen. */
	REUSE_DETECTED,

	/** The account was disabled. */
	ACCOUNT_DISABLED

}
