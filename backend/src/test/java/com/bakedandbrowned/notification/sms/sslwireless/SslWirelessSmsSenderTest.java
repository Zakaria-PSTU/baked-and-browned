package com.bakedandbrowned.notification.sms.sslwireless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.bakedandbrowned.notification.sms.SmsSender.SmsDeliveryException;
import com.bakedandbrowned.notification.sms.SmsSender.SmsMessage;
import com.bakedandbrowned.notification.sms.SmsSender.SmsRejectedException;

import tools.jackson.databind.json.JsonMapper;

/** ISMS Plus specifics (ADR-0027): what is sent, and how every documented outcome is classified. */
@ExtendWith(OutputCaptureExtension.class)
class SslWirelessSmsSenderTest {

	private static final String NUMBER = "+8801912345678";

	private static final String TEXT = "Baked & Browned: order BB-20261120-000001 received. Total BDT 1,310.00.";

	private final SslWirelessStub stub;

	private final SslWirelessSmsSender sender;

	SslWirelessSmsSenderTest() throws IOException {
		stub = new SslWirelessStub();
		sender = new SslWirelessSmsSender(stub.properties(), JsonMapper.builder().build());
	}

	@AfterEach
	void stop() {
		stub.close();
	}

	private static SmsMessage message(UUID key) {
		return new SmsMessage(key, NUMBER, TEXT);
	}

	@Test
	void sendsTheDocumentedFieldsAsJson() throws Exception {
		UUID key = UUID.fromString("0f1e2d3c-4b5a-4978-8a1b-2c3d4e5f6a7b");

		String id = sender.send(message(key));

		assertThat(stub.methods).containsExactly("POST");
		assertThat(stub.contentTypes.getFirst()).startsWith("application/json");
		Map<String, Object> sent = stub.requests.getFirst();
		assertThat(sent).containsOnlyKeys("api_token", "sid", "msisdn", "sms", "csms_id")
			.containsEntry("api_token", SslWirelessStub.TOKEN)
			.containsEntry("sid", SslWirelessStub.SENDER_ID)
			.containsEntry("msisdn", "8801912345678")
			.containsEntry("sms", TEXT)
			.containsEntry("csms_id", "0f1e2d3c4b5a49788a1b");
		assertThat(id).isNotBlank().isNotEqualTo("0f1e2d3c4b5a49788a1b");
		assertThat(sender.provider()).isEqualTo("ssl-wireless");
	}

	@Test
	void theClientReferenceIsTwentyHexDigitsAndStablePerNotice() {
		UUID key = UUID.randomUUID();

		assertThat(SslWirelessSmsSender.csmsId(key)).hasSize(20)
			.matches("[0-9a-f]{20}")
			.isEqualTo(SslWirelessSmsSender.csmsId(key))
			.isEqualTo(key.toString().replace("-", "").substring(0, 20));
	}

	@Test
	void aResendThatAlreadyGotThroughCountsAsSent(CapturedOutput output) throws Exception {
		UUID key = UUID.randomUUID();
		sender.send(message(key));

		// After a crash (or a lost response) the same notice goes again: the provider has it already.
		String id = sender.send(message(key));

		assertThat(id).isEqualTo(SslWirelessSmsSender.csmsId(key));
		assertThat(stub.requests).hasSize(2);
		assertThat(output).contains("duplicate");
	}

	@ParameterizedTest
	@ValueSource(ints = { 4020, 4024, 4025, 4026, 4027, 4028, 4032 })
	void permanentRejectionsAreNotRetried(int code) {
		stub.failNextWith(code);

		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsRejectedException.class)
			.extracting(ex -> ((SmsRejectedException) ex).code())
			.isEqualTo(String.valueOf(code));
	}

	@ParameterizedTest
	@ValueSource(strings = { "INVALID", "BLOCKED" })
	void aRefusedRecipientIsNotRetried(String smsStatus) {
		stub.refuseRecipientNext(smsStatus);

		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsRejectedException.class)
			.extracting(ex -> ((SmsRejectedException) ex).code())
			.isEqualTo(smsStatus);
	}

	@ParameterizedTest
	@ValueSource(ints = { 4001, 4002, 4003, 4004, 4005, 4022, 4035 })
	void configurationErrorsAreRetriedAndLoggedAsErrors(int code, CapturedOutput output) {
		stub.failNextWith(code);

		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class)
			.hasMessageContaining("configuration");
		assertThat(output).contains("ERROR").contains("code " + code);
	}

	@ParameterizedTest
	@ValueSource(ints = { 4029, 4031, 4033, 5000, 4999 })
	void temporaryAndUnknownCodesAreRetried(int code) {
		stub.failNextWith(code);

		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class);
	}

	@Test
	void aWrongTokenIsAConfigurationError() throws Exception {
		SslWirelessSmsSender wrong = new SslWirelessSmsSender(new SslWirelessProperties(true,
				URI.create(stub.baseUrl()), "not-the-token", SslWirelessStub.SENDER_ID, Duration.ofSeconds(2),
				Duration.ofSeconds(2)), JsonMapper.builder().build());

		assertThatThrownBy(() -> wrong.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class)
			.hasMessageContaining("4001");
	}

	@Test
	void serverErrorsAndUnreadableAnswersAreRetried() {
		stub.answerNextRaw(502, "<html>Bad gateway</html>");
		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class);

		stub.answerNextRaw(200, "");
		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class);

		stub.answerNextRaw(200, "[1,2]");
		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class);
	}

	@Test
	void anErrorWithANonOkStatusIsStillClassifiedByItsBody() {
		stub.answerNextRaw(400, "{\"status\":\"FAILED\",\"status_code\":4025,\"error_message\":\"Invalid MSISDN\"}");

		assertThatThrownBy(() -> sender.send(message(UUID.randomUUID()))).isInstanceOf(SmsRejectedException.class)
			.extracting(ex -> ((SmsRejectedException) ex).code())
			.isEqualTo("4025");
	}

	@Test
	void aTimeoutIsRetriedWithTheSameReferenceAndThenCountsAsSent() throws Exception {
		UUID key = UUID.randomUUID();
		sender.send(message(UUID.randomUUID()));
		stub.acceptSlowlyNext(SslWirelessStub.READ_TIMEOUT.plusSeconds(1));

		// The provider accepted it, but the answer came too late: ambiguous, so retried.
		assertThatThrownBy(() -> sender.send(message(key))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class)
			.hasMessageContaining("could not be reached");

		// Retried once the provider has recorded the first attempt.
		Thread.sleep(1500);
		assertThat(sender.send(message(key))).isEqualTo(SslWirelessSmsSender.csmsId(key));
		assertThat(stub.requests.subList(1, 3)).extracting(r -> r.get("csms_id"))
			.containsOnly(SslWirelessSmsSender.csmsId(key));
	}

	@Test
	void aProviderThatCannotBeReachedIsRetried() throws Exception {
		String base = stub.baseUrl();
		stub.close();
		SslWirelessSmsSender offline = new SslWirelessSmsSender(SslWirelessStub.properties(base),
				JsonMapper.builder().build());

		assertThatThrownBy(() -> offline.send(message(UUID.randomUUID()))).isInstanceOf(SmsDeliveryException.class)
			.isNotInstanceOf(SmsRejectedException.class);
	}

	@Test
	void aNumberThatIsNotABangladeshiMobileIsRejectedWithoutCallingTheProvider() {
		assertThatThrownBy(() -> sender.send(new SmsMessage(UUID.randomUUID(), "+447700900123", TEXT)))
			.isInstanceOf(SmsRejectedException.class);
		assertThat(stub.requests).isEmpty();
	}

	@Test
	void logsAndErrorsNeverShowTheTextTheFullNumberOrTheToken(CapturedOutput output) throws Exception {
		UUID key = UUID.randomUUID();
		sender.send(message(key));
		sender.send(message(key));
		stub.failNextWith(4003);
		Throwable configuration = catchThrowable(() -> sender.send(message(UUID.randomUUID())));
		stub.failNextWith(4025);
		Throwable rejected = catchThrowable(() -> sender.send(message(UUID.randomUUID())));
		stub.failNextWith(5000);
		Throwable temporary = catchThrowable(() -> sender.send(message(UUID.randomUUID())));

		for (String text : new String[] { output.getAll(), configuration.getMessage(), rejected.getMessage(),
				temporary.getMessage(), sender.toString(), stub.properties().toString() }) {
			assertThat(text).doesNotContain(TEXT, "BB-20261120", "8801912345678", "1912345678", SslWirelessStub.TOKEN);
		}
	}

	private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		return org.assertj.core.api.Assertions.catchThrowable(call);
	}

}
