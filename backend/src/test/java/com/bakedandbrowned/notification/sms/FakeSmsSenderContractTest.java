package com.bakedandbrowned.notification.sms;

import org.springframework.mock.env.MockEnvironment;

class FakeSmsSenderContractTest extends SmsSenderContract {

	private final FakeSmsSender sender = new FakeSmsSender(new MockEnvironment().withProperty("spring.profiles.active", "test"));

	@Override
	protected SmsSender sender() {
		return sender;
	}

}
