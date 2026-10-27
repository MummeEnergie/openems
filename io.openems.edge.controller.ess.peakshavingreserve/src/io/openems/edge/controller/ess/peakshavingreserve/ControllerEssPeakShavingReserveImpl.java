package io.openems.edge.controller.ess.peakshavingreserve;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;

import java.util.ArrayDeque;
import java.util.Deque;

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
import io.openems.edge.common.filter.PidFilter;
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
 * balancing). As soon as a subsequent controller asks for less discharging
 * (or more charging) than the bound, the battery follows the bound directly.
 * With base = meter + measured ESS power and the ESS measurement arriving 2-4 s
 * after the meter (Edge2Edge), the bound then oscillated (27.10.2026, bound
 * 170 kW against an external charging setpoint: grid swinging between +335 and
 * -191 kW). The base for this bound therefore uses the power the solver applied
 * in the previous cycle instead of the measurement, as long as the measurement
 * matches one of the recently applied setpoints (otherwise the battery does not
 * follow, e.g. when full, and the measurement counts), and is smoothed by a
 * first-order filter ({@link #BASE_FILTER_ALPHA}).
 * <li>SoC at or below the reserve, excess &gt; 0: {@code P_ess = excess} through
 * an own PID filter. Filtered, because an unfiltered bound oscillates with
 * delayed ESS measurements (observed with Edge2Edge: grid swinging +-100 kW).
 * Own filter, because the solver's shared filter is applied again by
 * subsequent controllers such as balancing within the same cycle, which
 * clamps its error sum and stalls the control below the target.
 * <li>SoC at or below the reserve, no peak: {@code excess <= P_ess <= 0}.
 * Discharging is prohibited, charging stays allowed up to the peak-shaving
 * power - unlike the standard controller, which holds the battery at 0.
 * <li>With {@code rechargeEnabled}: once the SoC has reached the reserve, the
 * reserve is actively recharged from the grid while there is no peak, up to
 * {@code reserveTargetSoc} (hysteresis). Charging power is
 * {@code min(rechargePower, peakShavingPower - rechargeMargin - base)},
 * through the same own PID filter; without headroom there is no charging. The
 * SoC band between reserve and target counts as reserve while recharging, so
 * subsequent controllers cannot discharge what was just recharged. A peak
 * interrupts recharging and is shaved as above.
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

	/**
	 * Smoothing of the base for the bound above the reserve, per cycle. With the
	 * applied setpoint as battery power, stable in simulation for actuator delays
	 * of 1-3 cycles (measured on the RevPi: 1-2 s); after a 65 kW load step the
	 * grid exceeds the peak-shaving power for about 3 cycles. With the measured
	 * battery power and alpha 0.25 (first version) it were 11-17 cycles: on
	 * 27.10.2026 at 13:25 the grid stayed above 380 kW for 20 s, up to 407 kW.
	 */
	static final double BASE_FILTER_ALPHA = 0.5;

	/** Number of applied setpoints the measurement may lag behind. */
	static final int SETPOINT_HISTORY = 6;

	/** Tolerance between measurement and an applied setpoint [W]. */
	static final int SETPOINT_MATCH_TOLERANCE = 30_000;

	private final Deque<Integer> appliedSetpoints = new ArrayDeque<>();

	private Config config;
	private PidFilter pidFilter;
	private Double filteredBase = null;
	private Mode mode = Mode.NONE;
	private boolean recharging = false;

	/** Which equality the own PID filter currently tracks. */
	private enum Mode {
		NONE, SHAVE, RECHARGE
	}

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
		if (config.reserveTargetSoc() < config.reserveSoc() || config.reserveTargetSoc() > 100) {
			throw new IllegalArgumentException("Recharge target SoC must be within " + config.reserveSoc()
					+ "..100, got " + config.reserveTargetSoc());
		}
		if (config.rechargePower() < 0 || config.rechargeMargin() < 0) {
			throw new IllegalArgumentException("Recharge power and margin must not be negative");
		}
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.pidFilter = new PidFilter(config.p(), config.i(), config.d());
		this.mode = Mode.NONE;
		this.recharging = false;
		this.filteredBase = null;
		this.appliedSetpoints.clear();
		this.channel(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE).setNextValue(false);
		this.channel(ControllerEssPeakShavingReserve.ChannelId.RESERVE_ACTIVE).setNextValue(false);
		this.channel(ControllerEssPeakShavingReserve.ChannelId.RECHARGE_ACTIVE).setNextValue(false);
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
		var rechargeActive = false;
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
			final long boundBase = (long) meter.getActivePower().getOrError()
					+ this.batteryPowerForBound(ess, ess.getActivePower().getOrError());
			this.filteredBase = this.filteredBase == null ? boundBase
					: this.filteredBase + BASE_FILTER_ALPHA * (boundBase - this.filteredBase);

			final var minimumPower = basePower - this.config.peakShavingPower();

			// Recharge hysteresis: start at the reserve, stop at the target
			if (!this.config.rechargeEnabled()) {
				this.recharging = false;
			} else if (soc <= this.config.reserveSoc()) {
				this.recharging = true;
			} else if (soc >= this.config.reserveTargetSoc()) {
				this.recharging = false;
			}

			if (soc <= this.config.reserveSoc() || this.recharging) {
				// Reserve rules apply: at or below the reserve, or recharging up to the target
				reserveActive = true;
				if (minimumPower > 0) {
					// Peak in reserve: discharge exactly the excess, filtered
					var power = ess.getPower();
					var lowest = power.getMinPower(ess, ALL, ACTIVE);
					var maximumPower = power.getMaxPower(ess, ALL, ACTIVE);
					limitUnfulfillable = minimumPower > maximumPower;
					this.switchMode(Mode.SHAVE);
					this.pidFilter.setLimits(lowest, Math.max(lowest, maximumPower));
					var target = clip(Math.min(minimumPower, maximumPower));
					var output = this.pidFilter.applyPidFilter(ess.getActivePower().getOrError(), target);
					ess.setActivePowerEquals(output);
					return;
				}
				if (this.recharging) {
					// Recharge below the peak-shaving power minus margin, at most rechargePower
					final long target = Math.max(minimumPower + this.config.rechargeMargin(),
							-(long) this.config.rechargePower());
					if (target < 0) {
						var lowest = Math.min(0, ess.getPower().getMinPower(ess, ALL, ACTIVE));
						this.switchMode(Mode.RECHARGE);
						this.pidFilter.setLimits(lowest, 0);
						var output = this.pidFilter.applyPidFilter(ess.getActivePower().getOrError(),
								clip(Math.max(target, lowest)));
						ess.setActivePowerEquals(output);
						rechargeActive = true;
						return;
					}
				}
				// No peak in reserve: no discharging, charging allowed (lower bound below)
				ess.setActivePowerLessOrEquals(0);
			}

			this.switchMode(Mode.NONE);
			final var boundPower = Math.round(this.filteredBase) - this.config.peakShavingPower();
			var maximumPower = ess.getPower().getMaxPower(ess, ALL, ACTIVE);
			limitUnfulfillable = boundPower > maximumPower;
			// Clip explicitly to avoid repeating the power API's adjustment log each cycle.
			// Negative bounds allow charging up to the peak-shaving power.
			ess.setActivePowerGreaterOrEquals(clip(Math.min(boundPower, maximumPower)));
		} finally {
			if (inputUnavailable) {
				// Start the smoothing afresh instead of from a stale base
				this.filteredBase = null;
				this.appliedSetpoints.clear();
			}
			this.channel(ControllerEssPeakShavingReserve.ChannelId.INPUT_UNAVAILABLE).setNextValue(inputUnavailable);
			this.channel(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE)
					.setNextValue(limitUnfulfillable);
			this.channel(ControllerEssPeakShavingReserve.ChannelId.RESERVE_ACTIVE).setNextValue(reserveActive);
			this.channel(ControllerEssPeakShavingReserve.ChannelId.RECHARGE_ACTIVE).setNextValue(rechargeActive);
		}
	}

	/**
	 * Battery power for the base of the bound above the reserve: the power the
	 * solver applied in the previous cycle, as long as the measurement matches
	 * one of the recently applied setpoints; otherwise the measurement.
	 *
	 * @param ess      the ESS
	 * @param measured the measured active power [W]
	 * @return the battery power to use [W]
	 */
	private int batteryPowerForBound(ManagedSymmetricEss ess, int measured) {
		var applied = ess.getDebugSetActivePower();
		if (!applied.isDefined()) {
			this.appliedSetpoints.clear();
			return measured;
		}
		this.appliedSetpoints.addFirst(applied.get());
		while (this.appliedSetpoints.size() > SETPOINT_HISTORY) {
			this.appliedSetpoints.removeLast();
		}
		for (var setpoint : this.appliedSetpoints) {
			if (Math.abs(setpoint - measured) <= SETPOINT_MATCH_TOLERANCE) {
				return applied.get();
			}
		}
		return measured;
	}

	private void switchMode(Mode next) {
		if (this.mode != next) {
			this.pidFilter.reset();
			this.mode = next;
		}
	}

	private static int clip(long value) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
	}
}
