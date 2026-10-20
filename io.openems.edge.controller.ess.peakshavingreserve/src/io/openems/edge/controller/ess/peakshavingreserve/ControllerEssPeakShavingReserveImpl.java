package io.openems.edge.controller.ess.peakshavingreserve;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Peak-shaving with a SoC reserve that is kept for peak-shaving only.
 *
 * <p>
 * Every cycle, with base = grid purchase without the battery (meter + ess) and
 * excess = base - peakShavingPower:
 *
 * <ul>
 * <li>SoC above the reserve: {@code P_ess >= excess}. An inequality only;
 * without a peak the operating point is left to subsequent controllers (e.g.
 * balancing).
 * <li>SoC at or below the reserve, excess &gt; 0: {@code P_ess = excess} through
 * the power PID filter, like Controller.Symmetric.PeakShaving in standard mode.
 * Filtered, because an unfiltered bound oscillates with delayed ESS
 * measurements (observed with Edge2Edge: grid swinging +-100 kW).
 * <li>SoC at or below the reserve, no peak: {@code excess <= P_ess <= 0}.
 * Discharging is prohibited, charging stays allowed up to the peak-shaving
 * power - unlike the standard controller, which holds the battery at 0.
 * </ul>
 *
 * <p>
 * No reactive-power target. Independent of Controller.Symmetric.PeakShaving;
 * switching back means disabling this one and enabling the standard
 * controller.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.PeakShavingReserve", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssPeakShavingReserveImpl extends AbstractOpenemsComponent
		implements ControllerEssPeakShavingReserve, Controller, OpenemsComponent {

	@Reference
	private ComponentManager componentManager;

	private Config config;

	public ControllerEssPeakShavingReserveImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssPeakShavingReserve.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		if (config.reserveSoc() < 0 || config.reserveSoc() > 100) {
			throw new IllegalArgumentException("Reserve SoC must be within 0..100, got " + config.reserveSoc());
		}
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.channel(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE).setNextValue(false);
		this.channel(ControllerEssPeakShavingReserve.ChannelId.RESERVE_ACTIVE).setNextValue(false);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
		var inputUnavailable = true;
		var limitUnfulfillable = false;
		var reserveActive = false;
		try {
			if (ess.getGridMode() == GridMode.OFF_GRID) {
				inputUnavailable = false;
				return;
			}
			if (ess.getGridMode() != GridMode.ON_GRID) {
				return;
			}

			ElectricityMeter meter = this.componentManager.getComponent(this.config.meter_id());
			// Read all measurements before setting any constraints. Never substitute zero.
			final var soc = ess.getSoc().getOrError();
			final long basePower = (long) meter.getActivePower().getOrError() + ess.getActivePower().getOrError();
			inputUnavailable = false;

			final var minimumPower = basePower - this.config.peakShavingPower();

			if (soc <= this.config.reserveSoc()) {
				reserveActive = true;
				if (minimumPower > 0) {
					// Peak in reserve: discharge exactly the excess, filtered
					var maximumPower = ess.getPower().getMaxPower(ess, ALL, ACTIVE);
					limitUnfulfillable = minimumPower > maximumPower;
					ess.setActivePowerEqualsWithPid(clip(Math.min(minimumPower, maximumPower)));
					return;
				}
				// No peak in reserve: no discharging, charging allowed
				ess.setActivePowerLessOrEquals(0);
			}

			var maximumPower = ess.getPower().getMaxPower(ess, ALL, ACTIVE);
			limitUnfulfillable = minimumPower > maximumPower;
			// Clip explicitly to avoid repeating the power API's adjustment log each cycle.
			// Negative bounds allow charging up to the peak-shaving power.
			ess.setActivePowerGreaterOrEquals(clip(Math.min(minimumPower, maximumPower)));
		} finally {
			this.channel(ControllerEssPeakShavingReserve.ChannelId.INPUT_UNAVAILABLE).setNextValue(inputUnavailable);
			this.channel(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE)
					.setNextValue(limitUnfulfillable);
			this.channel(ControllerEssPeakShavingReserve.ChannelId.RESERVE_ACTIVE).setNextValue(reserveActive);
		}
	}

	private static int clip(long value) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
	}
}
