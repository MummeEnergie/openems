package io.openems.edge.evcs.pricing.eaaze;

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
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.evcs.pricing.AbstractEvcsPricingExporter;
import io.openems.edge.evcs.pricing.EvcsPricing;
import io.openems.edge.evcs.pricing.EvcsPricingExporter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "EvcsPricing.Eaaze", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class EvcsPricingEaazeImpl extends AbstractEvcsPricingExporter implements EvcsPricingEaaze, OpenemsComponent {

	/** Connect timeout in milliseconds (5 minutes). */
	private static final int CONNECT_TIMEOUT_MS = 300_000;

	/** Read timeout in milliseconds (5 minutes). */
	private static final int READ_TIMEOUT_MS = 300_000;

	private static final String CURRENCY_EUR = "EUR";

	private final Logger log = LoggerFactory.getLogger(EvcsPricingEaazeImpl.class);

	@Reference(target = "(id=" + EvcsPricing.SINGLETON_COMPONENT_ID + ")")
	private EvcsPricing pricing;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;

	private BridgeHttp httpBridge;
	private Config config;

	public EvcsPricingEaazeImpl() {
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
		this.log.info("Eaaze exporter activated.");
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

		if (!this.validateConfig()) {
			this.cancelExport();
			return;
		}

		double taxRate = this.config.taxRate();
		double nettoPrice = priceEurPerKwh / (1 + taxRate);

		this.log.info("Exporting EVCS price to Eaaze: {} €/kWh brutto -> {} €/kWh netto (tax rate: {}%)",
				priceEurPerKwh, String.format("%.4f", nettoPrice), taxRate * 100);

		var graphqlQuery = buildUpdateCpoTariffMutation(//
				this.config.tariffId(), //
				this.config.tenantId(), //
				this.config.tariffName(), //
				nettoPrice, //
				taxRate //
		);

		this.log.debug("GraphQL mutation:\n{}", graphqlQuery);

		var requestBody = JsonUtils.buildJsonObject() //
				.addProperty("query", graphqlQuery) //
				.build();

		this.log.debug("Request body: {}", requestBody);

		var endpoint = new Endpoint(//
				this.config.graphqlUrl(), //
				HttpMethod.POST, //
				CONNECT_TIMEOUT_MS, //
				READ_TIMEOUT_MS, //
				requestBody.toString(), //
				Map.of(//
						"Content-Type", "application/json", //
						"Authorization", "M2M " + this.config.apiToken() //
				) //
		);

		this.httpBridge.requestJson(endpoint) //
				.whenComplete((response, error) -> {
					if (error != null) {
						this.log.error("Failed to export price to Eaaze: {}", error.getMessage());
						this._setHttpStatusCode(0);
						this.scheduleRetry(priceEurPerKwh);
						return;
					}

					var statusCode = response.status().code();
					this._setHttpStatusCode(statusCode);

					if (response.status().isError()) {
						this.log.error("Eaaze API error (HTTP {}): {}", statusCode, response.data());
						this.scheduleRetry(priceEurPerKwh);
						return;
					}

					var responseData = response.data();
					if (responseData.isJsonObject()) {
						var responseObj = responseData.getAsJsonObject();
						if (responseObj.has("errors") && !responseObj.get("errors").isJsonNull()) {
							this.log.error("Eaaze GraphQL error: {}", responseObj.get("errors"));
							this.scheduleRetry(priceEurPerKwh);
							return;
						}
					}

					this.onExportSuccess(priceEurPerKwh);
					this.log.info("Successfully exported price {} €/kWh to Eaaze", priceEurPerKwh);
				});
	}

	/**
	 * Validates that all required configuration fields are present.
	 *
	 * @return true if configuration is valid
	 */
	private boolean validateConfig() {
		if (this.config.apiToken().isBlank()) {
			this.log.warn("Cannot export price: API token is not configured");
			this._setHttpStatusCode(0);
			return false;
		}
		if (this.config.tariffId().isBlank()) {
			this.log.warn("Cannot export price: Tariff ID is not configured");
			this._setHttpStatusCode(0);
			return false;
		}
		if (this.config.tenantId().isBlank()) {
			this.log.warn("Cannot export price: Tenant ID is not configured");
			this._setHttpStatusCode(0);
			return false;
		}
		return true;
	}

	/**
	 * Builds the GraphQL mutation for updating a CPO tariff.
	 *
	 * @param tariffId    the UUID of the tariff to update
	 * @param tenantId    the tenant ID
	 * @param tariffName  the name of the tariff
	 * @param pricePerKwh the energy price in €/kWh (netto)
	 * @param taxRate     the VAT tax rate (e.g., 0.19 for 19%)
	 * @return the GraphQL mutation string
	 */
	private static String buildUpdateCpoTariffMutation(String tariffId, String tenantId, String tariffName,
			double pricePerKwh, double taxRate) {
		return String.format("""
				mutation UpdateCpoTariff {
				    updateCpoTariff(
				        id: "%s"
				        input: {
				            tenantId: "%s"
				            currency: "%s"
				            name: "%s"
				            priceComponents: [
				                {
				                    name: "Energy fee"
				                    feeType: ENERGY_FEE
				                    pricePerUnit: %.4f
				                    tax: %.2f
				                }
				            ]
				        }
				    ) {
				        id
				    }
				}
				""", //
				escapeGraphqlString(tariffId), //
				escapeGraphqlString(tenantId), //
				CURRENCY_EUR, //
				escapeGraphqlString(tariffName), //
				pricePerKwh, //
				taxRate //
		);
	}

	/**
	 * Escapes special characters in a string for use in GraphQL.
	 *
	 * @param input the input string to escape
	 * @return the escaped string safe for use in GraphQL
	 */
	private static String escapeGraphqlString(String input) {
		if (input == null) {
			return "";
		}
		return input //
				.replace("\\", "\\\\") //
				.replace("\"", "\\\"") //
				.replace("\n", "\\n") //
				.replace("\r", "\\r") //
				.replace("\t", "\\t");
	}
}
