package io.openems.edge.evcs.pricing.mumaxdisplay;

import java.util.Map;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttp.Endpoint;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.bridge.http.api.HttpMethod;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.evcs.pricing.AbstractEvcsPricingExporter;
import io.openems.edge.evcs.pricing.EvcsPricing;
import io.openems.edge.evcs.pricing.EvcsPricingExporter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "EvcsPricing.MumaxDisplay", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class EvcsPricingMumaxDisplayImpl extends AbstractEvcsPricingExporter
		implements EvcsPricingMumaxDisplay, OpenemsComponent {

	/** Connect timeout in milliseconds (5 minutes). */
	private static final int CONNECT_TIMEOUT_MS = 300_000;

	/** Read timeout in milliseconds (5 minutes). */
	private static final int READ_TIMEOUT_MS = 300_000;

	private final Logger log = LoggerFactory.getLogger(EvcsPricingMumaxDisplayImpl.class);

	@Reference(target = "(id=" + EvcsPricing.SINGLETON_COMPONENT_ID + ")")
	private EvcsPricing pricing;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;

	private BridgeHttp httpBridge;
	private Config config;

	public EvcsPricingMumaxDisplayImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				EvcsPricingExporter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		this.config = config;
		this.httpBridge = this.httpBridgeFactory.get();
		super.activate(context, config.id(), config.alias(), config.enabled(), //
				this.pricing, config.exportIntervalSeconds(), config.retryBackoffSeconds());
		this.log.info("MumaxDisplay exporter activated.");
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		this.config = config;
		super.modified(context, config.id(), config.alias(), config.enabled(), //
				config.exportIntervalSeconds(), config.retryBackoffSeconds());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
		this.httpBridgeFactory.unget(this.httpBridge);
		this.httpBridge = null;
	}

	@Override
	protected void doExport(Double priceEurPerKwh) {
		if (priceEurPerKwh == null || !Double.isFinite(priceEurPerKwh)) {
			this.log.warn("Cannot export invalid price: {}", priceEurPerKwh);
			this._setHttpStatusCode(0);
			this.cancelExport();
			return;
		}

		if (this.config.apiToken().isBlank()) {
			this.log.warn("Cannot export price: API token is not configured");
			this._setHttpStatusCode(0);
			this.cancelExport();
			return;
		}

		var priceInCents = Math.round(priceEurPerKwh * 100);

		this.log.info("Exporting EVCS price to Mumax display: {} €/kWh -> {} ct/kWh", priceEurPerKwh, priceInCents);

		var endpoint = new Endpoint(//
				this.config.url(), //
				HttpMethod.POST, //
				CONNECT_TIMEOUT_MS, //
				READ_TIMEOUT_MS, //
				String.valueOf(priceInCents), //
				Map.of("Authorization", "Bearer " + this.config.apiToken()) //
		);

		this.httpBridge.request(endpoint) //
				.whenComplete((response, error) -> {
					if (error != null) {
						this.log.error("Failed to export price to Mumax display: {}", error.getMessage());
						this._setHttpStatusCode(0);
						this.scheduleRetry(priceEurPerKwh);
						return;
					}

					var statusCode = response.status().code();
					this._setHttpStatusCode(statusCode);

					if (response.status().isError()) {
						this.log.error("Mumax display API error (HTTP {}): {}", statusCode, response.data());
						this.scheduleRetry(priceEurPerKwh);
						return;
					}

					this.onExportSuccess(priceEurPerKwh);
					this.log.info("Successfully exported price {} ct/kWh to Mumax display", priceInCents);
				});
	}
}
