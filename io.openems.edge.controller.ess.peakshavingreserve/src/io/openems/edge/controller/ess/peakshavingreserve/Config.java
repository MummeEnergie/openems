package io.openems.edge.controller.ess.peakshavingreserve;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller ESS Peak-Shaving with Reserve", //
		description = "Limits grid purchase to the peak-shaving power and keeps a SoC reserve for that purpose. "
				+ "Sets only inequality constraints; the operating point is left to subsequent controllers.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlPeakShavingReserve0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "ID of Ess device.")
	String ess_id();

	@AttributeDefinition(name = "Grid-Meter-ID", description = "ID of the Grid-Meter.")
	String meter_id();

	@AttributeDefinition(name = "Peak-Shaving power [W]", description = "Grid purchase above this value is shaved by discharging the battery.")
	int peakShavingPower();

	@AttributeDefinition(name = "Reserve SoC [%]", description = "At or below this SoC, the battery is discharged only as far as needed to keep grid purchase at the peak-shaving power. Charging stays allowed.")
	int reserveSoc() default 20;

	@AttributeDefinition(name = "Recharge reserve?", description = "Below the reserve SoC and without a peak, actively charge from the grid until the target SoC is reached. Grid purchase stays at least the recharge margin below the peak-shaving power.")
	boolean rechargeEnabled() default false;

	@AttributeDefinition(name = "Recharge target SoC [%]", description = "Recharging started at or below the reserve SoC continues up to this SoC (hysteresis). Must not be below the reserve SoC.")
	int reserveTargetSoc() default 22;

	@AttributeDefinition(name = "Max. recharge power [W]", description = "Upper limit of the charging power while recharging the reserve.")
	int rechargePower() default 200000;

	@AttributeDefinition(name = "Recharge margin [W]", description = "While recharging, grid purchase is kept this far below the peak-shaving power.")
	int rechargeMargin() default 20000;

	@AttributeDefinition(name = "PID P", description = "Proportional gain of the own PID filter used for shaving within the reserve.")
	double p() default 0.3;

	@AttributeDefinition(name = "PID I", description = "Integral gain of the own PID filter used for shaving within the reserve.")
	double i() default 0.3;

	@AttributeDefinition(name = "PID D", description = "Derivative gain of the own PID filter used for shaving within the reserve.")
	double d() default 0.1;

	String webconsole_configurationFactory_nameHint() default "Controller ESS Peak-Shaving with Reserve [{id}]";
}
