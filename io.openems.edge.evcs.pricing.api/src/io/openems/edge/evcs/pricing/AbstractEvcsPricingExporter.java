package io.openems.edge.evcs.pricing;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.utils.ThreadPoolUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;

/**
 * Abstract base class for EVCS pricing exporters. Manages the shared
 * scheduling, retry, and state-tracking logic via the template method pattern.
 *
 * <p>
 * Subclasses implement {@link #doExport(Double)} to perform the actual HTTP
 * call, then call {@link #onExportSuccess(Double)} on success or
 * {@link #scheduleRetry(Double)} on failure.
 *
 * <p>
 * Because OSGi {@code @Reference} annotations are only processed on concrete
 * {@code @Component} classes, the {@link EvcsPricing} reference must be passed
 * in by the concrete subclass from its own {@code @Activate} method.
 */
public abstract class AbstractEvcsPricingExporter extends AbstractOpenemsComponent implements EvcsPricingExporter {

	private static final int MAX_RETRIES = 3;

	private final Logger log = LoggerFactory.getLogger(AbstractEvcsPricingExporter.class);

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private volatile Double lastExportedPrice = null;
	/**
	 * Price whose export was given up after {@link #MAX_RETRIES}. The price
	 * channel is set every cycle while an override is active, so without this the
	 * same failing price would start a new export right after giving up.
	 */
	private volatile Double lastFailedPrice = null;
	private final AtomicInteger retryCount = new AtomicInteger(0);
	private final AtomicBoolean exportInProgress = new AtomicBoolean(false);
	/** Incremented on every config change; retries of older generations drop. */
	private final AtomicInteger generation = new AtomicInteger(0);

	private EvcsPricing pricing;
	private volatile boolean enabled;
	private int exportIntervalSeconds;
	private int retryBackoffSeconds;

	protected AbstractEvcsPricingExporter(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	/**
	 * Called by the concrete subclass from its {@code @Activate} method. Sets up
	 * price-change subscription, periodic scheduling, and triggers an initial
	 * export.
	 *
	 * @param context               OSGi component context
	 * @param id                    component ID
	 * @param alias                 component alias
	 * @param enabled               whether the component is enabled
	 * @param pricing               the {@link EvcsPricing} reference
	 * @param exportIntervalSeconds interval between periodic exports
	 * @param retryBackoffSeconds   base delay between retry attempts
	 */
	protected void activate(ComponentContext context, String id, String alias, boolean enabled, EvcsPricing pricing,
			int exportIntervalSeconds, int retryBackoffSeconds) {
		super.activate(context, id, alias, enabled);
		this.enabled = enabled;
		this.pricing = pricing;
		this.exportIntervalSeconds = exportIntervalSeconds;
		this.retryBackoffSeconds = retryBackoffSeconds;
		this.subscribeToPriceChanges();
		this.schedulePeriodicExport();
		this.tryExport(true);
	}

	/**
	 * Called by the concrete subclass from its {@code @Modified} method. Resets
	 * export state, drops pending retries, and re-triggers an export with the
	 * latest price.
	 *
	 * @param context               OSGi component context
	 * @param id                    component ID
	 * @param alias                 component alias
	 * @param enabled               whether the component is enabled
	 * @param exportIntervalSeconds interval between periodic exports
	 * @param retryBackoffSeconds   base delay between retry attempts
	 */
	protected void modified(ComponentContext context, String id, String alias, boolean enabled,
			int exportIntervalSeconds, int retryBackoffSeconds) {
		super.modified(context, id, alias, enabled);
		this.generation.incrementAndGet();
		this.enabled = enabled;
		this.exportIntervalSeconds = exportIntervalSeconds;
		this.retryBackoffSeconds = retryBackoffSeconds;
		this.lastExportedPrice = null;
		this.lastFailedPrice = null;
		this.exportInProgress.set(false);
		this.retryCount.set(0);
		this.tryExport(true);
	}

	@Override
	protected void deactivate() {
		this.enabled = false;
		this.generation.incrementAndGet();
		super.deactivate();
		ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
	}

	private void subscribeToPriceChanges() {
		this.pricing.getPriceChannel().onSetNextValue(ignore -> this.tryExport(false));
	}

	private void schedulePeriodicExport() {
		this.executor.scheduleAtFixedRate(() -> this.tryExport(true), this.exportIntervalSeconds,
				this.exportIntervalSeconds, TimeUnit.SECONDS);
	}

	/**
	 * Starts an export if the price differs from the last exported one.
	 *
	 * @param regular true for the periodic export and config changes, which also
	 *                retry a price whose export was given up; false for price
	 *                channel updates, which skip such a price
	 */
	void tryExport(boolean regular) { // package-private for testing
		if (!this.enabled) {
			return;
		}

		if (this.exportInProgress.get()) {
			return;
		}

		var price = this.pricing.getPrice().get();
		if (price == null) {
			price = this.pricing.getPriceChannel().getNextValue().get();
		}
		if (price == null) {
			this.log.debug("Price is null, skipping export");
			return;
		}

		if (Objects.equals(this.lastExportedPrice, price)) {
			return;
		}

		if (!regular && Objects.equals(this.lastFailedPrice, price)) {
			return;
		}

		if (!this.exportInProgress.compareAndSet(false, true)) {
			return;
		}
		this.retryCount.set(0);
		this.doExport(price);
	}

	/**
	 * Performs the actual export of the price to the target system. Implementations
	 * must call {@link #onExportSuccess(Double)} on success,
	 * {@link #scheduleRetry(Double)} on a transient failure, or
	 * {@link #cancelExport()} if the export cannot proceed (e.g. invalid config).
	 *
	 * @param priceEurPerKwh the current EVCS price in €/kWh
	 */
	protected abstract void doExport(Double priceEurPerKwh);

	/**
	 * Called by subclasses when an export succeeds. Resets retry state, records the
	 * last exported price, and clears the failure channel.
	 *
	 * @param priceEurPerKwh the successfully exported price in €/kWh
	 */
	protected void onExportSuccess(Double priceEurPerKwh) {
		this.retryCount.set(0);
		this.lastExportedPrice = priceEurPerKwh;
		this.lastFailedPrice = null;
		this.exportInProgress.set(false);
		this._setExportFailed(false);
	}

	/**
	 * Schedules a retry with linear backoff ({@code attempt * retryBackoffSeconds}).
	 * After {@link #MAX_RETRIES} attempts the export is abandoned and
	 * {@link EvcsPricingExporter.ChannelId#EXPORT_FAILED} is set. The same price
	 * is then only retried by the next periodic export or a config change; a
	 * different price is exported right away.
	 *
	 * <p>
	 * Nothing is retried once the component is disabled or its config changed.
	 *
	 * @param priceEurPerKwh the price to retry exporting in €/kWh
	 */
	protected void scheduleRetry(Double priceEurPerKwh) {
		if (!this.enabled) {
			this.retryCount.set(0);
			this.exportInProgress.set(false);
			return;
		}
		var attempt = this.retryCount.incrementAndGet();
		if (attempt > MAX_RETRIES) {
			this.log.error("Export failed after {} retries, giving up until next periodic export or price change",
					MAX_RETRIES);
			this.retryCount.set(0);
			this.lastFailedPrice = priceEurPerKwh;
			this.exportInProgress.set(false);
			this._setExportFailed(true);
			return;
		}
		var delaySec = attempt * this.retryBackoffSeconds;
		var scheduledGeneration = this.generation.get();
		this.log.warn("Scheduling retry {}/{} in {} seconds", attempt, MAX_RETRIES, delaySec);
		this.executor.schedule(() -> {
			if (!this.enabled || scheduledGeneration != this.generation.get()) {
				this.log.info("Dropping scheduled retry: component disabled or reconfigured");
				return;
			}
			this.doExport(priceEurPerKwh);
		}, delaySec, TimeUnit.SECONDS);
	}

	/**
	 * Cancels the current export cycle without recording a failure. Use when the
	 * export cannot proceed due to invalid configuration or an unrecoverable
	 * precondition. The next regular trigger (periodic or price change) will retry.
	 */
	protected void cancelExport() {
		this.exportInProgress.set(false);
	}
}
