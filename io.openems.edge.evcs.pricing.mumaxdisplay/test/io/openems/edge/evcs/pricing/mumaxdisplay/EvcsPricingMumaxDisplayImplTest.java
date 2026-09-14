package io.openems.edge.evcs.pricing.mumaxdisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.common.bridge.http.api.HttpMethod;
import io.openems.common.bridge.http.api.HttpResponse;
import io.openems.common.bridge.http.dummy.DummyBridgeHttpBundle;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.evcs.pricing.DummyEvcsPricing;

public class EvcsPricingMumaxDisplayImplTest {

	@Test
	public void testExportWithCommonHttpBridge() throws Exception {
		final var url = "https://example.test/display";
		final var http = DummyBridgeHttpBundle.of();
		final var requestCalled = http.expect(endpoint -> {
			assertEquals(HttpMethod.POST, endpoint.method());
			assertEquals("Bearer token", endpoint.properties().get("Authorization"));
			assertEquals("42", endpoint.body());
			return endpoint.url().equals(url);
		}).toBeCalled();
		http.forceNextSuccessfulResult(HttpResponse.ok("ok"));

		final var pricing = new DummyEvcsPricing();
		pricing.getPriceChannel().setNextValue(0.42);
		final var test = new ComponentTest(new EvcsPricingMumaxDisplayImpl()) //
				.addReference("pricing", pricing) //
				.addReference("httpBridgeFactory", http.factory()) //
				.activate(new MyConfig("evcsPricingMumaxDisplay0", url, "token"));

		http.runTasksImmediately();
		assertTrue(requestCalled.get());
		test.deactivate();
	}
}
