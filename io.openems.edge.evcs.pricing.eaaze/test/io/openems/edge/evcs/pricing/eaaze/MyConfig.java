package io.openems.edge.evcs.pricing.eaaze;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	private final String alias;
	private final String graphqlUrl;
	private final String apiToken;
	private final String tariffId;
	private final String tenantId;

	public MyConfig(String id, String graphqlUrl, String apiToken, String tariffId, String tenantId) {
		super(Config.class, id);
		this.alias = id;
		this.graphqlUrl = graphqlUrl;
		this.apiToken = apiToken;
		this.tariffId = tariffId;
		this.tenantId = tenantId;
	}

	@Override
	public String alias() {
		return this.alias;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public int exportIntervalSeconds() {
		return 1800;
	}

	@Override
	public String graphqlUrl() {
		return this.graphqlUrl;
	}

	@Override
	public String apiToken() {
		return this.apiToken;
	}

	@Override
	public String tariffId() {
		return this.tariffId;
	}

	@Override
	public String tenantId() {
		return this.tenantId;
	}

	@Override
	public String tariffName() {
		return "Test tariff";
	}

	@Override
	public double taxRate() {
		return 0.19;
	}

	@Override
	public int retryBackoffSeconds() {
		return 30;
	}

	@Override
	public String webconsole_configurationFactory_nameHint() {
		return "Evcs Pricing Eaaze Exporter [" + this.id() + "]";
	}
}
