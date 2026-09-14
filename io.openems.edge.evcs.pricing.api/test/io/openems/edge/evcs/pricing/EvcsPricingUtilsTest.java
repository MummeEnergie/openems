package io.openems.edge.evcs.pricing;

import static org.junit.Assert.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.Test;

import io.openems.edge.timeofusetariff.test.DummyTimeOfUseTariffProvider;

public class EvcsPricingUtilsTest {

	private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	public void testComputeAverageCtKwhUsesExclusiveUpperBound() {
		var tariff = DummyTimeOfUseTariffProvider.fromQuarterlyPrices(CLOCK, 100., 1_000.);
		var result = EvcsPricingUtils.computeAverageCtKwh(tariff, NOW.atZone(ZoneOffset.UTC),
				new DummyEvcsPricing());

		assertEquals(10., result.orElseThrow(), 0.001);
	}
}
