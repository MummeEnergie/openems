package io.openems.edge.controller.symmetric.peakshaving;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Symmetric.PeakShaving", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssPeakShavingImpl extends AbstractOpenemsComponent
        implements ControllerEssPeakShaving, Controller, OpenemsComponent {

    public static final double DEFAULT_MAX_ADJUSTMENT_RATE = 0.2;

    private final Logger log = LoggerFactory.getLogger(ControllerEssPeakShavingImpl.class);

    @Reference
    private ComponentManager componentManager;

    private Config config;

    public ControllerEssPeakShavingImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerEssPeakShaving.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        if (config.limitOnly() && (config.socInfimum() < 0 || config.socSupremum() > 100
                || config.socInfimum() >= config.socSupremum())) {
            throw new IllegalArgumentException("Limit-only mode requires 0 <= socInfimum < socSupremum <= 100");
        }
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.channel(ControllerEssPeakShaving.ChannelId.LIMIT_UNFULFILLABLE).setNextValue(false);
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
        if (this.config.limitOnly()) {
            this.runLimitOnly(ess);
            return;
        }
        /*
         * Check that the SoC is in the defined range
         */
        var currentSoC = ess.getSoc().getOrError();
        if (currentSoC < this.config.socInfimum() || currentSoC > this.config.socSupremum()) {
            return;
        }

        /*
         * Check that we are On-Grid (and warn on undefined Grid-Mode)
         */
        var gridMode = ess.getGridMode();
        if (gridMode.isUndefined()) {
            this.logWarn(this.log, "Grid-Mode is [UNDEFINED]");
        }
        switch (gridMode) {
            case ON_GRID:
            case UNDEFINED:
                break;
            case OFF_GRID:
                return;
        }

        ElectricityMeter meter = this.componentManager.getComponent(this.config.meter_id());

        // Calculate 'real' grid-power (without current ESS charge/discharge)
        var gridPower = meter.getActivePower().getOrError()/* current buy-from/sell-to grid */
                + ess.getActivePower().getOrError() /* current charge/discharge Ess */;

        var calculatedPower = 0;

        if (gridPower >= this.config.peakShavingPower()) {
            /*
             * Peak-Shaving
             */
            calculatedPower = gridPower - this.config.peakShavingPower();
        } else if (gridPower <= this.config.rechargePower()) {
            /*
             * Recharge
             */
            calculatedPower = gridPower - this.config.rechargePower();
        }
        ess.setActivePowerEqualsWithPid(calculatedPower);
        ess.setReactivePowerEquals(0);
    }

    private void runLimitOnly(ManagedSymmetricEss ess) throws OpenemsNamedException {
        var inputUnavailable = true;
        var limitUnfulfillable = false;
        try {
            if (ess.getGridMode() == GridMode.OFF_GRID) {
                inputUnavailable = false;
                return;
            }
            if (ess.getGridMode() != GridMode.ON_GRID) {
                return;
            }

            ElectricityMeter meter = this.componentManager.getComponent(this.config.meter_id());
            var soc = ess.getSoc().getOrError();
            // Read all measurements before setting any constraints. Never substitute zero.
            final long basePower = (long) meter.getActivePower().getOrError() + ess.getActivePower().getOrError();
            inputUnavailable = false;

            // Protect only the prohibited direction; preceding protection still has priority.
            if (soc <= this.config.socInfimum()) {
                ess.setActivePowerLessOrEquals(0);
            }
            if (soc >= this.config.socSupremum()) {
                ess.setActivePowerGreaterOrEquals(0);
            }

            var minimumPower = basePower - this.config.peakShavingPower();
            var maximumPower = ess.getPower().getMaxPower(ess, ALL, ACTIVE);
            limitUnfulfillable = minimumPower > maximumPower;
            // Clip explicitly to avoid repeating the power API's adjustment log each cycle.
            // Do not filter or clamp the lower bound to zero: negative bounds allow charging.
            ess.setActivePowerGreaterOrEquals((int) Math.max(Integer.MIN_VALUE, Math.min(minimumPower, maximumPower)));
        } finally {
            this.channel(ControllerEssPeakShaving.ChannelId.INPUT_UNAVAILABLE).setNextValue(inputUnavailable);
            this.channel(ControllerEssPeakShaving.ChannelId.LIMIT_UNFULFILLABLE).setNextValue(limitUnfulfillable);
        }
    }
}
