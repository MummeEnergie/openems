package io.openems.edge.evcs.pricing.eaaze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.common.bridge.http.api.HttpMethod;
import io.openems.common.bridge.http.api.HttpResponse;
import io.openems.common.bridge.http.dummy.DummyBridgeHttpBundle;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.evcs.pricing.DummyEvcsPricing;

public class EvcsPricingEaazeImplTest {

	@Test
	public void testExportWithCommonHttpBridge() throws Exception {
		final var url = "https://example.test/graphql";
		final var http = DummyBridgeHttpBundle.of();
		final var requestCalled = http.expect(endpoint -> {
			assertEquals(HttpMethod.POST, endpoint.method());
			assertEquals("M2M token", endpoint.properties().get("Authorization"));
			return endpoint.url().equals(url) && endpoint.body().contains("updateCpoTariff");
		}).toBeCalled();
		http.forceNextSuccessfulResult(HttpResponse.ok("{}"));

		final var pricing = new DummyEvcsPricing();
		pricing.getPriceChannel().setNextValue(0.42);
		final var test = new ComponentTest(new EvcsPricingEaazeImpl()) //
				.addReference("pricing", pricing) //
				.addReference("httpBridgeFactory", http.factory()) //
				.activate(new MyConfig("evcsPricingEaaze0", url, "token", "tariff", "tenant"));

		http.runTasksImmediately();
		assertTrue(requestCalled.get());
		test.deactivate();
	}
}
