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

	String webconsole_configurationFactory_nameHint() default "Controller ESS Peak-Shaving with Reserve [{id}]";
}
