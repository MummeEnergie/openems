package io.openems.edge.evcs.technagon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.Test;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.test.ComponentTest;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.evcs.test.DummyEvcsPower;
import io.openems.edge.meter.api.PhaseRotation;

public class EvcsTechnagonImplTest {

	@Test
	public void testActivationAndProtocolForBothChargePoints() throws Exception {
		for (var chargePoint : ChargePoint.values()) {
			var sut = activate(chargePoint, true);
			var tasks = sut.protocol().getTaskManager().getTasks();
			var addresses = tasks.stream().map(task -> task.getStartAddress()).toList();
			var offset = chargePoint.getOffset();
			assertTrue(addresses.containsAll(List.of(0x0008, offset + 0x003, offset + 0x008, offset + 0x018,
					offset + 0x100, offset + 0x101, offset + 0x102, offset + 0x103)));
			assertEquals(4, tasks.stream().filter(FC3ReadRegistersTask.class::isInstance).count());
			assertEquals(4, tasks.stream().filter(FC6WriteRegisterTask.class::isInstance).count());
			assertEquals(PhaseRotation.L1_L2_L3, sut.getPhaseRotation());
		}
	}

	@Test
	public void testTelemetryScaling() throws Exception {
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0x0008, 0, 123, 0, 32000, 2) //
				.withRegister(0x1003, 3) //
				.withRegisters(0x1008, 6000, 32000, 16000, 2300, 2310, 2320, 1000, 990, 980, 1000, 2000,
						3000, 230, 462, 696, 1388) //
				.withRegisters(0x1018, 0, 0, 0, 1234);
		var sut = activate(ChargePoint.CP1, true, bridge);
		sut.protocol().getTaskManager().getTasks().stream() //
				.filter(FC3ReadRegistersTask.class::isInstance) //
				.forEach(task -> task.execute(bridge));

		assertEquals(Integer.valueOf(230000), sut.getVoltageL1Channel().getNextValue().get());
		assertEquals(Integer.valueOf(231000), sut.getVoltageL2Channel().getNextValue().get());
		assertEquals(Integer.valueOf(232000), sut.getVoltageL3Channel().getNextValue().get());
		assertEquals(Integer.valueOf(16000), sut.getOfferedCurrentChannel().getNextValue().get());
		assertEquals(Integer.valueOf(1000), sut.getCurrentL1Channel().getNextValue().get());
		assertEquals(Integer.valueOf(230), sut.getActivePowerL1Channel().getNextValue().get());
		assertEquals(Integer.valueOf(1388), sut.getActivePowerChannel().getNextValue().get());
		assertEquals(Long.valueOf(1234), sut.getActiveProductionEnergyChannel().getNextValue().get());
	}

	@Test
	public void testStatusMapping() {
		assertEquals(Status.NOT_READY_FOR_CHARGING, EvcsTechnagonImpl.mapStatus(0));
		assertEquals(Status.NOT_READY_FOR_CHARGING, EvcsTechnagonImpl.mapStatus(1));
		assertEquals(Status.READY_FOR_CHARGING, EvcsTechnagonImpl.mapStatus(2));
		assertEquals(Status.CHARGING, EvcsTechnagonImpl.mapStatus(3));
		assertEquals(Status.CHARGING_REJECTED, EvcsTechnagonImpl.mapStatus(4));
		assertEquals(Status.READY_FOR_CHARGING, EvcsTechnagonImpl.mapStatus(5));
		assertEquals(Status.CHARGING_FINISHED, EvcsTechnagonImpl.mapStatus(6));
		assertEquals(Status.NOT_READY_FOR_CHARGING, EvcsTechnagonImpl.mapStatus(10));
		assertEquals(Status.ERROR, EvcsTechnagonImpl.mapStatus(99));
		assertEquals(Status.UNDEFINED, EvcsTechnagonImpl.mapStatus(-1));
		assertEquals(Status.UNDEFINED, EvcsTechnagonImpl.mapStatus(null));
	}

	@Test
	public void testCurrentConversionAndClamping() {
		assertEquals(0, EvcsTechnagonImpl.calculateTargetCurrent(0, 3, 6000, 32000));
		assertEquals(6000, EvcsTechnagonImpl.calculateTargetCurrent(1000, 1, 6000, 32000));
		assertEquals(10000, EvcsTechnagonImpl.calculateTargetCurrent(6900, 3, 6000, 32000));
		assertEquals(15000, EvcsTechnagonImpl.calculateTargetCurrent(6900, 2, 6000, 32000));
		assertEquals(30000, EvcsTechnagonImpl.calculateTargetCurrent(6900, 1, 6000, 32000));
		assertEquals(32000, EvcsTechnagonImpl.calculateTargetCurrent(50000, 1, 6000, 32000));
		assertEquals(10000, EvcsTechnagonImpl.calculateTargetCurrent(6900, 0, 6000, 32000));
	}

	@Test
	public void testRejectsInvalidRegisterValues() {
		assertInvalidConfig(MyConfig.create().setMinHwCurrent(-1).build());
		assertInvalidConfig(MyConfig.create().setMinHwCurrent(65536).build());
		assertInvalidConfig(MyConfig.create().setMaxHwCurrent(-1).build());
		assertInvalidConfig(MyConfig.create().setMaxHwCurrent(65536).build());
		assertInvalidConfig(MyConfig.create().setMinHwCurrent(32000).setMaxHwCurrent(6000).build());
		assertInvalidConfig(MyConfig.create().setFallbackCurrent(-1).build());
		assertInvalidConfig(MyConfig.create().setFallbackCurrent(65536).build());
		assertInvalidConfig(MyConfig.create().setFallbackTimeout(-1).build());
		assertInvalidConfig(MyConfig.create().setFallbackTimeout(65536).build());
	}

	@Test
	public void testSessionEnergyLifecycle() throws Exception {
		var sut = activate(ChargePoint.CP1, true);
		setTelemetry(sut, 2, 1000L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(0), sut.getEnergySessionChannel().getNextValue().get());
		setTelemetry(sut, 3, 1250L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(250), sut.getEnergySessionChannel().getNextValue().get());
		setTelemetry(sut, 6, 1300L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(300), sut.getEnergySessionChannel().getNextValue().get());
		setTelemetry(sut, 1, 1400L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(0), sut.getEnergySessionChannel().getNextValue().get());
		setTelemetry(sut, 3, 1500L);
		sut.updateStatusAndSessionEnergy();
		setTelemetry(sut, 10, 1550L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(50), sut.getEnergySessionChannel().getNextValue().get());
		setTelemetry(sut, 99, 1600L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(100), sut.getEnergySessionChannel().getNextValue().get());
	}

	@Test
	public void testSessionEnergyInvalidAndRange() throws Exception {
		var sut = activate(ChargePoint.CP1, true);
		setTelemetry(sut, 3, 1000L);
		sut.updateStatusAndSessionEnergy();
		setTelemetry(sut, 3, 500L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(0), sut.getEnergySessionChannel().getNextValue().get());
		setTelemetry(sut, 3, Long.MAX_VALUE);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), sut.getEnergySessionChannel().getNextValue().get());
		sut.getRawStatusChannel().setNextValue(null);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(0), sut.getEnergySessionChannel().getNextValue().get());
	}

	@Test
	public void testSessionEnergyDisabled() throws Exception {
		var sut = activate(ChargePoint.CP1, false);
		setTelemetry(sut, 3, 1000L);
		sut.updateStatusAndSessionEnergy();
		assertEquals(Integer.valueOf(0), sut.getEnergySessionChannel().getNextValue().get());
	}

	@Test
	public void testCyclicSafetyAndCurrentWrites() throws Exception {
		var sut = activate(ChargePoint.CP1, true);
		sut.handleEvent(new Event(EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, new HashMap<>()));
		assertEquals(Integer.valueOf(10000), sut.getPercentSetpointChannel().getNextWriteValue().get());
		assertEquals(Integer.valueOf(0), sut.getFallbackCurrentChannel().getNextWriteValue().get());
		assertEquals(Integer.valueOf(30), sut.getFallbackTimeoutChannel().getNextWriteValue().get());
		sut.applyChargePowerLimit(6900);
		assertEquals(Integer.valueOf(10000), sut.getCurrentSetpointChannel().getNextWriteValue().get());
		sut.pauseChargeProcess();
		assertEquals(Integer.valueOf(0), sut.getCurrentSetpointChannel().getNextWriteValue().get());
	}

	private static TestableEvcsTechnagonImpl activate(ChargePoint chargePoint, boolean readEnergy) throws Exception {
		return activate(chargePoint, readEnergy, new DummyModbusBridge("modbus0"));
	}

	private static TestableEvcsTechnagonImpl activate(ChargePoint chargePoint, boolean readEnergy,
			DummyModbusBridge bridge)
			throws Exception {
		var sut = new TestableEvcsTechnagonImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("evcsPower", new DummyEvcsPower()) //
				.addReference("setModbus", bridge) //
				.activate(MyConfig.create().setChargePoint(chargePoint).setReadEnergyRegister(readEnergy).build());
		return sut;
	}

	private static void setTelemetry(EvcsTechnagonImpl sut, int rawStatus, long energy) {
		sut.getRawStatusChannel().setNextValue(rawStatus);
		sut.getActiveProductionEnergyChannel().setNextValue(energy);
	}

	private static void assertInvalidConfig(Config config) {
		assertThrows(ConfigurationException.class, () -> EvcsTechnagonImpl.validate(config));
	}

	private static class TestableEvcsTechnagonImpl extends EvcsTechnagonImpl {

		@Override
		protected void activate(ComponentContext context, Config config)
				throws OpenemsException, ConfigurationException {
			super.activate(context, config);
		}

		private ModbusProtocol protocol() {
			return this.getModbusProtocol();
		}
	}
}
