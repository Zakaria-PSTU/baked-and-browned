package com.bakedandbrowned.payment.service;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentProvider;

/**
 * {@code app.payments.manual.*} (ADR-0011, ADR-0022): where customers send a MANUAL payment. These
 * are the business's own published receiving accounts, not secrets, but they differ per deployment,
 * so they come from configuration. Empty (the default) means manual payment is not offered.
 */
@ConfigurationProperties("app.payments.manual")
public record ManualPaymentProperties(@Nullable List<Account> accounts) {

	public ManualPaymentProperties {
		accounts = accounts == null ? List.of() : List.copyOf(accounts);
	}

	/**
	 * One receiving account, shown to the customer as configured.
	 *
	 * @param label e.g. "bKash (Merchant)"
	 * @param accountName the name the customer should see when sending, e.g. the business name
	 * @param accountNumber MFS number or bank account number, as the customer types it
	 * @param note optional short instruction, e.g. "Use Payment, not Send Money"
	 */
	public record Account(ManualPaymentProvider provider, String label, String accountName, String accountNumber,
			@Nullable String note) {

		public Account {
			Objects.requireNonNull(provider, "app.payments.manual.accounts[].provider");
			label = required(label, "label", 80);
			accountName = required(accountName, "account-name", 120);
			accountNumber = required(accountNumber, "account-number", 60);
			if (note != null) {
				note = note.strip();
				if (note.isEmpty()) {
					note = null;
				}
				else if (note.length() > 300) {
					throw new IllegalArgumentException("app.payments.manual.accounts[].note: at most 300 characters");
				}
			}
		}

		private static String required(@Nullable String value, String name, int max) {
			String stripped = value == null ? "" : value.strip();
			if (stripped.isEmpty() || stripped.length() > max) {
				throw new IllegalArgumentException(
						"app.payments.manual.accounts[]." + name + ": required, at most " + max + " characters");
			}
			return stripped;
		}

	}

}
