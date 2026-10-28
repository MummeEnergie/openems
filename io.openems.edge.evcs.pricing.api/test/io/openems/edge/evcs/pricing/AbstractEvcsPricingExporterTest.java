package io.openems.edge.evcs.pricing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.DummyComponentContext;

public class AbstractEvcsPricingExporterTest {

	private static final int INTERVAL_SECONDS = 3600;

	/** Records every export attempt; fails or succeeds on demand. */
	private static class TestExporter extends AbstractEvcsPricingExporter {

		private final List<Double> exports = new CopyOnWriteArrayList<>();
		private volatile boolean fail = true;

		private TestExporter() {
			super(OpenemsComponent.ChannelId.values(), EvcsPricingExporter.ChannelId.values());
		}

		private void start(EvcsPricing pricing, int retryBackoffSeconds) {
			this.activate(new DummyComponentContext(), "exporter0", "", true, pricing, INTERVAL_SECONDS,
					retryBackoffSeconds);
		}

		private void reconfigure(boolean enabled, int retryBackoffSeconds) {
			this.modified(new DummyComponentContext(), "exporter0", "", enabled, INTERVAL_SECONDS,
					retryBackoffSeconds);
		}

		@Override
		protected void doExport(Double priceEurPerKwh) {
			this.exports.add(priceEurPerKwh);
			if (this.fail) {
				this.scheduleRetry(priceEurPerKwh);
			} else {
				this.onExportSuccess(priceEurPerKwh);
			}
		}
	}

	private DummyEvcsPricing pricing;
	private TestExporter exporter;

	@Before
	public void setUp() {
		this.pricing = new DummyEvcsPricing();
		this.exporter = new TestExporter();
	}

	@After
	public void tearDown() {
		this.exporter.deactivate();
	}

	private void awaitExports(int count) throws InterruptedException {
		for (var i = 0; i < 100 && this.exporter.exports.size() < count; i++) {
			Thread.sleep(50);
		}
		assertEquals(count, this.exporter.exports.size());
	}

	private boolean exportFailed() {
		return Boolean.TRUE.equals(this.exporter.getExportFailedChannel().getNextValue().get());
	}

	/**
	 * First attempt plus three retries, then give up on that price.
	 *
	 * @param price the price that keeps failing
	 * @throws InterruptedException if interrupted while waiting
	 */
	private void failPriceCompletely(double price) throws InterruptedException {
		this.exporter.start(this.pricing, 0);
		this.pricing._setPrice(price);
		this.awaitExports(4);
		Thread.sleep(200);
		assertEquals(4, this.exporter.exports.size());
		assertTrue(this.exportFailed());
	}

	@Test
	public void samePriceIsNotRetriedEveryCycleAfterGivingUp() throws InterruptedException {
		this.failPriceCompletely(0.48);

		// The pricing core sets the price channel every cycle while an override
		// is active.
		for (var i = 0; i < 20; i++) {
			this.pricing._setPrice(0.48);
		}
		Thread.sleep(300);

		assertEquals(4, this.exporter.exports.size());
	}

	@Test
	public void newPriceIsExportedAfterGivingUp() throws InterruptedException {
		this.failPriceCompletely(0.48);
		this.exporter.fail = false;

		this.pricing._setPrice(0.45);

		this.awaitExports(5);
		assertEquals(0.45, this.exporter.exports.get(4), 0.0);
		assertFalse(this.exportFailed());
	}

	@Test
	public void periodicExportRetriesTheFailedPrice() throws InterruptedException {
		this.failPriceCompletely(0.48);
		this.exporter.fail = false;

		this.exporter.tryExport(true);

		this.awaitExports(5);
		assertEquals(0.48, this.exporter.exports.get(4), 0.0);
		assertFalse(this.exportFailed());
	}

	@Test
	public void scheduledRetryIsDroppedWhenDisabled() throws InterruptedException {
		this.exporter.start(this.pricing, 1);
		this.pricing._setPrice(0.48);
		this.awaitExports(1);

		this.exporter.reconfigure(false, 1);
		Thread.sleep(1500);

		assertEquals(1, this.exporter.exports.size());
	}

	@Test
	public void scheduledRetryOfOldConfigIsDroppedAfterReconfigure() throws InterruptedException {
		this.exporter.start(this.pricing, 2);
		this.pricing._setPrice(0.48);
		this.awaitExports(1);

		// Exports right away and schedules its own first retry after 2 s.
		this.exporter.reconfigure(true, 2);
		this.awaitExports(2);

		// Only the new config's first retry runs; the old one is dropped.
		Thread.sleep(3000);
		assertEquals(3, this.exporter.exports.size());
	}
}
