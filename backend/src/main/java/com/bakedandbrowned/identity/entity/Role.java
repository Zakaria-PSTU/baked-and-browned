package com.bakedandbrowned.identity.entity;

/** PostgreSQL {@code user_role}. Public registration only ever assigns {@link #CUSTOMER}. */
public enum Role {

	CUSTOMER, ADMIN

}
