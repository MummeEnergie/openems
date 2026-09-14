package io.openems.edge.controller.symmetric.limitactivepower;

import static io.openems.common.utils.IntUtils.fitWithin;
import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static io.openems.edge.ess.power.api.Relationship.GREATER_OR_EQUALS;
import static io.openems.edge.ess.power.api.Relationship.LESS_OR_EQUALS;

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
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Symmetric.LimitActivePower", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssLimitActivePowerImpl extends AbstractOpenemsComponent
        implements ControllerEssLimitActivePower, Controller, OpenemsComponent {

    @Reference
    private ComponentManager componentManager;

    private Config config;

    /**
     * The configured Max Charge ActivePower.
     *
     * <p>
     * Value is zero or negative
     */
    private int maxChargePower = 0;

    /**
     * The configured Max Discharge ActivePower.
     *
     * <p>
     * Value is zero or positive
     */
    private int maxDischargePower = 0;

    public ControllerEssLimitActivePowerImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerEssLimitActivePower.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.maxChargePower = config.maxChargePower() * -1;
        this.maxDischargePower = config.maxDischargePower();
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        if (this.config.mode() == Mode.MANUAL_OFF) {
            return;
        }

        ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());

        var startTime = getDateFromIsoString(this.config.startTime());
        var endTime = getDateFromIsoString(this.config.endTime());
        var currentTime = new Date();
        if (startTime != null && startTime.after(currentTime)) {
            // do nothing
            return;
        }
        if (endTime != null && endTime.before(currentTime)) {
            // do nothing
            return;
        }

        if (this.config.validatePowerConstraints()) {

            // adjust value so that it fits into Min/MaxActivePower
            var maxPower = ess.getPower().getMaxPower(ess, ALL, ACTIVE);
            var minPower = ess.getPower().getMinPower(ess, ALL, ACTIVE);
            var calculatedMaxDischargePower = fitWithin(minPower, maxPower, this.maxDischargePower);
            var calculatedMaxChargePower = fitWithin(minPower, maxPower, this.maxChargePower);
            // set result
            ess.addPowerConstraintAndValidate("SymmetricLimitActivePower", ALL, ACTIVE,
                    GREATER_OR_EQUALS, calculatedMaxChargePower);
            ess.addPowerConstraintAndValidate("SymmetricLimitActivePower", ALL, ACTIVE,
                    LESS_OR_EQUALS, calculatedMaxDischargePower);
        } else {

            ess.addPowerConstraint("SymmetricLimitActivePower", ALL, ACTIVE, GREATER_OR_EQUALS,
                    this.maxChargePower);
            ess.addPowerConstraint("SymmetricLimitActivePower", ALL, ACTIVE, LESS_OR_EQUALS,
                    this.maxDischargePower);
        }
    }

    private static Date getDateFromIsoString(String iso8601String) {
        if (iso8601String == null || iso8601String.isBlank()) {
            return null;
        }
        DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_DATE_TIME;
        OffsetDateTime offsetDateTime = OffsetDateTime.parse(iso8601String, timeFormatter);

        return Date.from(Instant.from(offsetDateTime));
    }
}
