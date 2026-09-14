package io.openems.edge.evcs.pricing.mumaxdisplay;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	private final String alias;
	private final String url;
	private final String apiToken;

	public MyConfig(String id, String url, String apiToken) {
		super(Config.class, id);
		this.alias = id;
		this.url = url;
		this.apiToken = apiToken;
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
	public String url() {
		return this.url;
	}

	@Override
	public String apiToken() {
		return this.apiToken;
	}

	@Override
	public int exportIntervalSeconds() {
		return 1800;
	}

	@Override
	public int retryBackoffSeconds() {
		return 30;
	}

	@Override
	public String webconsole_configurationFactory_nameHint() {
		return "Evcs Pricing Mumax Display Exporter [" + this.id() + "]";
	}
}
