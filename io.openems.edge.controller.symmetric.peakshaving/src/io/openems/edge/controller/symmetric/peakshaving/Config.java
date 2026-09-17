package io.openems.edge.controller.symmetric.peakshaving;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Peak-Shaving Symmetric", //
		description = "Cuts power peaks and recharges the battery in low consumption periods.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlPeakShaving0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "ID of Ess device.")
	String ess_id();

	@AttributeDefinition(name = "Grid-Meter-ID", description = "ID of the Grid-Meter.")
	String meter_id();

	@AttributeDefinition(name = "Limit only", description = "Only limit grid import, leaving the operating point to subsequent controllers. No recharge regulation, PID filter or reactive-power target. SoC limits prohibit only the respective direction.")
	boolean limitOnly() default false;

	@AttributeDefinition(name = "Peak-Shaving power", description = "Grid purchase power above this value is considered a peak and shaved to this value.")
	int peakShavingPower();

	@AttributeDefinition(name = "Recharge power", description = "If grid purchase power is below this value battery is recharged. Ignored in limit-only mode.")
	int rechargePower();

	@AttributeDefinition(name = "Lower Limit SoC", description = "Lower limit of the SoC range. In limit-only mode, discharging is prohibited at or below this value. Defaults to 0.")
	int socInfimum() default 0;

	@AttributeDefinition(name = "Upper Limit SoC", description = "Upper limit of the SoC range. In limit-only mode, charging is prohibited at or above this value. Defaults to 100.")
	int socSupremum() default 100;

	String webconsole_configurationFactory_nameHint() default "Controller Peak-Shaving Symmetric [{id}]";
}
