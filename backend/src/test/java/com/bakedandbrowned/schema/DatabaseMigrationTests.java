package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The migrated schema as a whole: history, server version, reference data, structure. */
class DatabaseMigrationTests extends AbstractSchemaTest {

	@Test
	void allDocumentedMigrationsAreAppliedSuccessfullyInOrder() {
		List<Map<String, Object>> history = jdbc.queryForList("""
				SELECT version, script, success FROM flyway_schema_history
				WHERE version IS NOT NULL ORDER BY installed_rank""");

		assertThat(history).extracting(row -> row.get("script")).containsExactly(
				"V1__init.sql", "V2__foundation_hardening.sql", "V3__delivery_zones.sql",
				"V4__ordering_policy.sql", "V5__payments_manual_refunds.sql", "V6__notifications.sql", "V7__refund_policy.sql",
				"V8__online_payments.sql", "V9__payment_reconciliation.sql",
				"V10__option_costs.sql", "V11__disallowed_combinations.sql",
				"V12__customization_display_order.sql", "V13__absolute_session_lifetime.sql",
				"V14__notice_outcomes.sql");
		assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
	}

	@Test
	void runsOnPostgresql17() {
		Integer versionNum = jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);
		assertThat(versionNum).isBetween(170000, 179999);
	}

	@Test
	void seedsOnlyInactiveExampleZonesWithoutFees() {
		List<Map<String, Object>> zones = jdbc.queryForList(
				"SELECT code, name, delivery_fee, min_order_amount, active FROM delivery_zones ORDER BY sort_order");

		assertThat(zones).extracting(row -> row.get("name"))
			.containsExactly("Dhanmondi", "Mohammadpur", "Mirpur", "Uttara", "Gulshan", "Banani");
		assertThat(zones).allSatisfy(row -> {
			assertThat(row.get("active")).isEqualTo(false);
			assertThat(row.get("delivery_fee")).isNull();
			assertThat(row.get("min_order_amount")).isNull();
		});
	}

	@Test
	void migrationsSeedNoUsersOrCredentials() {
		// Checked on a fresh table state: reset only truncates, and migrations ran before any test.
		Integer seededBeforeTests = jdbc.queryForObject("""
				SELECT count(*) FROM flyway_schema_history h
				WHERE h.script LIKE 'V%' AND h.success""", Integer.class);
		assertThat(seededBeforeTests).isEqualTo(14);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
	}

	@Test
	void reservationStatusSupportsOneTimeConsumption() {
		List<String> values = jdbc.queryForList(
				"SELECT unnest(enum_range(NULL::reservation_status))::text", String.class);
		assertThat(values).containsExactly("HELD", "CONFIRMED", "RELEASED", "CONSUMED");
	}

	@Test
	void moneyColumnsUseNumeric12Scale2AndInstantsUseTimestamptz() {
		List<Map<String, Object>> wrongMoney = jdbc.queryForList("""
				SELECT table_name, column_name FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND column_name IN ('base_price','price_delta','subtotal','delivery_fee','discount_amount',
				                      'total_amount','unit_base_price','unit_options_price','unit_price',
				                      'line_total','amount','min_order_amount')
				  AND NOT (data_type = 'numeric' AND numeric_precision = 12 AND numeric_scale = 2)""");
		assertThat(wrongMoney).as("money columns not NUMERIC(12,2)").isEmpty();

		List<Map<String, Object>> naiveTimestamps = jdbc.queryForList("""
				SELECT table_name, column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
				  AND table_name <> 'flyway_schema_history'""");
		assertThat(naiveTimestamps).as("timestamp columns without time zone").isEmpty();
	}

	@Test
	void appendOnlyAndUpdatedAtTriggersAreInstalled() {
		List<String> triggers = jdbc.queryForList("""
				SELECT DISTINCT trigger_name FROM information_schema.triggers
				WHERE trigger_schema = 'public' ORDER BY trigger_name""", String.class);
		assertThat(triggers).contains(
				"trg_audit_events_append_only", "trg_status_history_append_only",
				"trg_inventory_movements_append_only", "trg_users_updated_at", "trg_products_updated_at",
				"trg_orders_updated_at", "trg_payments_updated_at", "trg_delivery_zones_updated_at",
				"trg_refunds_updated_at");
	}

}
