package io.openems.edge.evcs.technagon;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.evcs.api.Evcs.addCalculatePowerLimitListeners;
import static io.openems.edge.evcs.api.Evcs.calculateUsedPhasesFromCurrent;
import static io.openems.edge.meter.api.ElectricityMeter.calculateSumCurrentFromPhases;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedQuadruplewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.evcs.api.ChargeStateHandler;
import io.openems.edge.evcs.api.ChargingType;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.EvcsPower;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.evcs.api.WriteHandler;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.PhaseRotation;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Evcs.Technagon", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE })
public class EvcsTechnagonImpl extends AbstractOpenemsModbusComponent implements EvcsTechnagon, Evcs, ManagedEvcs,
		ElectricityMeter, ModbusComponent, OpenemsComponent, EventHandler {

	private static final int PERCENT_100 = 10_000;
	private static final int UNSIGNED_WORD_MAX = 0xFFFF;
	private static final int VOLTAGE = 230;

	private final Logger log = LoggerFactory.getLogger(EvcsTechnagonImpl.class);
	private final ChargeStateHandler chargeStateHandler = new ChargeStateHandler(this);
	private final WriteHandler writeHandler = new WriteHandler(this);

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private EvcsPower evcsPower;

	private Config config;
	private Long sessionBaseline;
	private int lastCommandedPower;
	private int lastPhaseCount = 3;
	private int lastTargetCurrent;

	public EvcsTechnagonImpl() {
		super(OpenemsComponent.ChannelId.values(), ElectricityMeter.ChannelId.values(), Evcs.ChannelId.values(),
				ManagedEvcs.ChannelId.values(), ModbusComponent.ChannelId.values(), EvcsTechnagon.ChannelId.values());
		calculateUsedPhasesFromCurrent(this);
		calculateSumCurrentFromPhases(this);
		addCalculatePowerLimitListeners(this);
		this.getModbusCommunicationFailedChannel()
				.onSetNextValue(value -> this._setChargingstationCommunicationFailed(value.orElse(true)));
	}

	@Activate
	protected void activate(ComponentContext context, Config config) throws OpenemsException, ConfigurationException {
		validate(config);
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setChargingType(ChargingType.AC);
		this._setPhases(3);
		this._setFixedMinimumHardwarePower(currentToThreePhasePower(config.minHwCurrent()));
		this._setFixedMaximumHardwarePower(currentToThreePhasePower(config.maxHwCurrent()));
		this._setPowerPrecision(0.23);
		this._setEnergySession(0);
	}

	static void validate(Config config) throws ConfigurationException {
		if (config.minHwCurrent() <= 0 || config.minHwCurrent() > UNSIGNED_WORD_MAX) {
			throw new ConfigurationException("minHwCurrent", "Must be between 1 and 65535");
		}
		if (config.maxHwCurrent() <= 0 || config.maxHwCurrent() > UNSIGNED_WORD_MAX) {
			throw new ConfigurationException("maxHwCurrent", "Must be between 1 and 65535");
		}
		if (config.minHwCurrent() > config.maxHwCurrent()) {
			throw new ConfigurationException("minHwCurrent", "Must not exceed maxHwCurrent");
		}
		if (config.fallbackCurrent() < 0 || config.fallbackCurrent() > UNSIGNED_WORD_MAX
				|| config.fallbackCurrent() > config.maxHwCurrent()) {
			throw new ConfigurationException("fallbackCurrent", "Must be between 0 and maxHwCurrent");
		}
		if (config.fallbackTimeout() < 0 || config.fallbackTimeout() > UNSIGNED_WORD_MAX) {
			throw new ConfigurationException("fallbackTimeout", "Must be between 0 and 65535");
		}
	}

	@Deactivate
	@Override
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var offset = this.config.chargePoint().getOffset();
		var protocol = new ModbusProtocol(this,
				new FC3ReadRegistersTask(0x0008, Priority.LOW,
						m(EvcsTechnagon.ChannelId.SERIAL_NUMBER, new UnsignedDoublewordElement(0x0008)),
						new DummyRegisterElement(0x000A),
						m(EvcsTechnagon.ChannelId.STATION_MAX_CURRENT, new UnsignedWordElement(0x000B)),
						m(EvcsTechnagon.ChannelId.CHARGE_POINT_COUNT, new UnsignedWordElement(0x000C))),
				new FC3ReadRegistersTask(offset + 0x003, Priority.HIGH,
						m(EvcsTechnagon.ChannelId.RAW_STATUS, new UnsignedWordElement(offset + 0x003))),
				new FC3ReadRegistersTask(offset + 0x008, Priority.HIGH,
						m(EvcsTechnagon.ChannelId.MIN_CURRENT, new UnsignedWordElement(offset + 0x008)),
						m(EvcsTechnagon.ChannelId.MAX_CURRENT, new UnsignedWordElement(offset + 0x009)),
						m(EvcsTechnagon.ChannelId.OFFERED_CURRENT, new UnsignedWordElement(offset + 0x00A)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(offset + 0x00B), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(offset + 0x00C), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(offset + 0x00D), SCALE_FACTOR_2),
						m(EvcsTechnagon.ChannelId.POWER_FACTOR_L1, new UnsignedWordElement(offset + 0x00E)),
						m(EvcsTechnagon.ChannelId.POWER_FACTOR_L2, new UnsignedWordElement(offset + 0x00F)),
						m(EvcsTechnagon.ChannelId.POWER_FACTOR_L3, new UnsignedWordElement(offset + 0x010)),
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(offset + 0x011)),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(offset + 0x012)),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(offset + 0x013)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new UnsignedWordElement(offset + 0x014)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new UnsignedWordElement(offset + 0x015)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new UnsignedWordElement(offset + 0x016)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new UnsignedWordElement(offset + 0x017))),
				new FC6WriteRegisterTask(offset + 0x100,
						m(EvcsTechnagon.ChannelId.PERCENT_SETPOINT, new UnsignedWordElement(offset + 0x100))),
				new FC6WriteRegisterTask(offset + 0x101,
						m(EvcsTechnagon.ChannelId.CURRENT_SETPOINT, new UnsignedWordElement(offset + 0x101))),
				new FC6WriteRegisterTask(offset + 0x102,
						m(EvcsTechnagon.ChannelId.FALLBACK_CURRENT, new UnsignedWordElement(offset + 0x102))),
				new FC6WriteRegisterTask(offset + 0x103,
						m(EvcsTechnagon.ChannelId.FALLBACK_TIMEOUT, new UnsignedWordElement(offset + 0x103))));
		if (this.config.readEnergyRegister()) {
			protocol.addTask(new FC3ReadRegistersTask(offset + 0x018, Priority.LOW,
					m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
							new UnsignedQuadruplewordElement(offset + 0x018))));
		}
		return protocol;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.updateStatusAndSessionEnergy();
		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE -> {
			try {
				this.getPercentSetpointChannel().setNextWriteValue(PERCENT_100);
				this.getFallbackCurrentChannel().setNextWriteValue(this.config.fallbackCurrent());
				this.getFallbackTimeoutChannel().setNextWriteValue(this.config.fallbackTimeout());
			} catch (Exception e) {
				this.logWarn(this.log, "Unable to queue Technagon safety settings: " + e.getMessage());
			}
			this.writeHandler.run();
		}
		default -> {
		}
		}
	}

	void updateStatusAndSessionEnergy() {
		var raw = this.getRawStatusChannel().getNextValue().orElse(null);
		this._setStatus(mapStatus(raw));
		if (!this.config.readEnergyRegister()) {
			this.sessionBaseline = null;
			this._setEnergySession(0);
			return;
		}
		var current = this.getActiveProductionEnergyChannel().getNextValue().orElse(null);
		if (raw == null || raw == 0 || raw == 1 || !isKnownStatus(raw)) {
			this.sessionBaseline = null;
			this._setEnergySession(0);
			return;
		}
		if (raw >= 2 && raw <= 5 && this.sessionBaseline == null && current != null) {
			this.sessionBaseline = current;
		}
		if (this.sessionBaseline == null || current == null || current < 0 || this.sessionBaseline < 0) {
			this._setEnergySession(0);
			return;
		}
		if (current < this.sessionBaseline) {
			this._setEnergySession(0);
			return;
		}
		var difference = current - this.sessionBaseline;
		this._setEnergySession((int) Math.min(Integer.MAX_VALUE, difference));
	}

	private static boolean isKnownStatus(int raw) {
		return raw == 0 || raw == 1 || raw == 2 || raw == 3 || raw == 4 || raw == 5 || raw == 6 || raw == 10
				|| raw == 99;
	}

	/**
	 * Maps Technagon raw status to OpenEMS EVCS status.
	 *
	 * @param raw raw Technagon status
	 * @return OpenEMS EVCS status
	 */
	public static Status mapStatus(Integer raw) {
		if (raw == null) {
			return Status.UNDEFINED;
		}
		return switch (raw) {
		case 0, 1 -> Status.NOT_READY_FOR_CHARGING;
		case 2 -> Status.READY_FOR_CHARGING;
		case 3 -> Status.CHARGING;
		case 4 -> Status.CHARGING_REJECTED;
		case 5 -> Status.READY_FOR_CHARGING;
		case 6 -> Status.CHARGING_FINISHED;
		case 10 -> Status.NOT_READY_FOR_CHARGING;
		case 99 -> Status.ERROR;
		default -> Status.UNDEFINED;
		};
	}

	/**
	 * Converts charge power to current and applies configured hardware limits.
	 *
	 * @param power          charge power in W
	 * @param phases         detected phase count
	 * @param minimumCurrent minimum hardware current in mA
	 * @param maximumCurrent maximum hardware current in mA
	 * @return target current in mA
	 */
	public static int calculateTargetCurrent(int power, int phases, int minimumCurrent, int maximumCurrent) {
		if (power <= 0) {
			return 0;
		}
		var actualPhases = phases >= 1 && phases <= 3 ? phases : 3;
		var current = Math.round(power * 1000F / actualPhases / VOLTAGE);
		return Math.max(minimumCurrent, Math.min(maximumCurrent, current));
	}

	@Override
	public boolean applyChargePowerLimit(int power) throws Exception {
		var phases = Evcs.evaluatePhaseCountFromCurrent(this.getCurrentL1().get(), this.getCurrentL2().get(),
				this.getCurrentL3().get());
		this.lastPhaseCount = phases == null ? 3 : phases;
		this.lastCommandedPower = power;
		this.lastTargetCurrent = calculateTargetCurrent(power, this.lastPhaseCount, this.config.minHwCurrent(),
				this.config.maxHwCurrent());
		this.setCurrentSetpoint(this.lastTargetCurrent);
		this.logDebug("Technagon command: power=" + power + " W, phases=" + this.lastPhaseCount + ", target="
				+ this.lastTargetCurrent + " mA, offered=" + this.getOfferedCurrentChannel().value().orElse(null) + " mA");
		return true;
	}

	@Override
	public boolean pauseChargeProcess() throws Exception {
		this.setCurrentSetpoint(0);
		return true;
	}

	@Override
	public boolean applyDisplayText(String text) {
		return false;
	}

	@Override
	public int getMinimumTimeTillChargingLimitTaken() {
		return 0;
	}

	@Override
	public ChargeStateHandler getChargeStateHandler() {
		return this.chargeStateHandler;
	}

	@Override
	public EvcsPower getEvcsPower() {
		return this.evcsPower;
	}

	@Override
	public int getConfiguredMinimumHardwarePower() {
		return currentToThreePhasePower(this.config.minHwCurrent());
	}

	@Override
	public int getConfiguredMaximumHardwarePower() {
		return currentToThreePhasePower(this.config.maxHwCurrent());
	}

	private static int currentToThreePhasePower(int current) {
		return Math.round(current / 1000F * VOLTAGE * 3);
	}

	@Override
	public boolean getConfiguredDebugMode() {
		return this.config.debugMode();
	}

	@Override
	public void logDebug(String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.MANAGED_CONSUMPTION_METERED;
	}

	@Override
	public PhaseRotation getPhaseRotation() {
		return PhaseRotation.L1_L2_L3;
	}

	@Override
	public String debugLog() {
		var base = "Status:" + this.getStatus().getName() + "|Offered:"
				+ this.getOfferedCurrentChannel().value().orElse(null) + "mA|Power:" + this.getActivePower().asString();
		if (!this.config.debugMode()) {
			return base;
		}
		return base + "|Command:" + this.lastCommandedPower + "W|Phases:" + this.lastPhaseCount + "|Target:"
				+ this.lastTargetCurrent + "mA";
	}
}
