package io.openems.edge.ess.core.power;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.edge.ess.power.api.SolverStrategy;

/** Configuration for the real power solver used by the peak-shaving tests. */
public class PeakShavingPowerConfig extends AbstractComponentConfig implements Config {

	public PeakShavingPowerConfig() {
		super(Config.class, "_power");
	}

	@Override
	public SolverStrategy strategy() {
		return SolverStrategy.OPTIMIZE_BY_MOVING_TOWARDS_TARGET;
	}

	@Override
	public boolean symmetricMode() {
		return true;
	}

	@Override
	public boolean debugMode() {
		return false;
	}

	@Override
	public boolean enablePid() {
		return true;
	}

	@Override
	public double p() {
		return 0.3;
	}

	@Override
	public double i() {
		return 0.3;
	}

	@Override
	public double d() {
		return 0.1;
	}
}
