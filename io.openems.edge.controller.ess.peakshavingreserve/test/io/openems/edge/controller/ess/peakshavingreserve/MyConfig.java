package io.openems.edge.controller.ess.peakshavingreserve;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String essId;
		private String meterId;
		private int peakShavingPower;
		private int reserveSoc = 20;
		private double p = 0.3;
		private double i = 0.3;
		private double d = 0.1;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setEssId(String essId) {
			this.essId = essId;
			return this;
		}

		public Builder setMeterId(String meterId) {
			this.meterId = meterId;
			return this;
		}

		public Builder setPeakShavingPower(int peakShavingPower) {
			this.peakShavingPower = peakShavingPower;
			return this;
		}

		public Builder setReserveSoc(int reserveSoc) {
			this.reserveSoc = reserveSoc;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String ess_id() {
		return this.builder.essId;
	}

	@Override
	public String meter_id() {
		return this.builder.meterId;
	}

	@Override
	public int peakShavingPower() {
		return this.builder.peakShavingPower;
	}

	@Override
	public int reserveSoc() {
		return this.builder.reserveSoc;
	}

	@Override
	public double p() {
		return this.builder.p;
	}

	@Override
	public double i() {
		return this.builder.i;
	}

	@Override
	public double d() {
		return this.builder.d;
	}

}
