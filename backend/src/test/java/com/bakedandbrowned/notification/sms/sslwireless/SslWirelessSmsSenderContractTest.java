package com.bakedandbrowned.notification.sms.sslwireless;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;

import com.bakedandbrowned.notification.sms.SmsSender;
import com.bakedandbrowned.notification.sms.SmsSenderContract;

import tools.jackson.databind.json.JsonMapper;

class SslWirelessSmsSenderContractTest extends SmsSenderContract {

	private final SslWirelessStub stub;

	private final SslWirelessSmsSender sender;

	SslWirelessSmsSenderContractTest() throws IOException {
		stub = new SslWirelessStub();
		sender = new SslWirelessSmsSender(stub.properties(), JsonMapper.builder().build());
	}

	@AfterEach
	void stop() {
		stub.close();
	}

	@Override
	protected SmsSender sender() {
		return sender;
	}

}
