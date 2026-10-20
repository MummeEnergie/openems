package io.openems.edge.controller.ess.peakshavingreserve;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import io.openems.common.exceptions.InvalidValueException;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.controller.ess.balancing.ControllerEssBalancingImpl;
import io.openems.edge.controller.ess.balancing.PeakShavingReserveBalancingConfig;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.core.power.EssPower;
import io.openems.edge.ess.core.power.EssPowerImpl;
import io.openems.edge.ess.core.power.PeakShavingReservePowerConfig;
import io.openems.edge.ess.test.AbstractDummyManagedSymmetricEss.SymmetricApplyPowerRecord;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.meter.test.DummyElectricityMeter;

/**
 * Peak-shaving power 380 kW, reserve 20 %. Grid meter 200 kW, battery idle,
 * SoC 50 % unless stated otherwise.
 */
public class ControllerEssPeakShavingReserveImplTest {

	// ---------------------------------------------------------- above reserve

	@Test
	public void allowsExternalChargingBelowGridLimit() throws Exception {
		var f = new Fixture();
		assertEquals(-100_000, f.cycle(-100_000));
	}

	@Test
	public void limitsChargingToGridLimit() throws Exception {
		var f = new Fixture();
		// 200 kW load, grid may rise to 380 kW: at most 180 kW charging
		assertEquals(-180_000, f.cycle(-300_000));
	}

	@Test
	public void requiresDischargingAboveGridLimit() throws Exception {
		var f = new Fixture();
		f.meter.withActivePower(500_000);
		assertEquals(120_000, f.cycle(0));
	}

	@Test
	public void permitsDischargingBeyondRequiredMinimumAboveReserve() throws Exception {
		var f = new Fixture();
		f.meter.withActivePower(500_000);
		assertEquals(300_000, f.cycle(300_000));
	}

	// --------------------------------------------------------------- reserve

	@Test
	public void reserveBlocksDischargingWithoutPeak() throws Exception {
		var f = new Fixture();
		for (int soc : new int[] { 20, 10 }) {
			f.ess.withSoc(soc);
			assertEquals(0, f.cycle(100_000));
			assertTrue(f.state(ControllerEssPeakShavingReserve.ChannelId.RESERVE_ACTIVE));
		}
	}

	@Test
	public void reserveShavesThePeakInsteadOfBlockingIt() throws Exception {
		// The case that failed with Controller.Symmetric.PeakShaving in limit-only
		// mode: at the lower SoC limit, no discharging at all - the peak went to
		// the grid. Here the first filtered step already discharges.
		var f = new Fixture();
		f.ess.withSoc(20);
		f.meter.withActivePower(500_000);
		f.controller.run();
		var first = f.solve();
		assertTrue("Reserve must discharge on a peak", first > 0);
		assertTrue("Reserve must not discharge beyond the excess", first <= 120_000);
		assertFalse(f.state(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE));
	}

	@Test
	public void reserveConvergesToGridLimit() throws Exception {
		for (var delay = 0; delay <= 3; delay++) {
			var grid = new Fixture().simulate(500_000, 20, 80, delay, false);
			assertSettled("delay " + delay, grid, 380_000, 10_000);
		}
	}

	@Test
	public void reserveConvergesTogetherWithBalancing() throws Exception {
		var grid = new Fixture().simulate(500_000, 20, 80, 1, true);
		assertSettled("with balancing", grid, 380_000, 10_000);
	}

	@Test
	public void reserveStillAllowsChargingUpToGridLimit() throws Exception {
		var f = new Fixture();
		f.ess.withSoc(15);
		assertEquals(-100_000, f.cycle(-100_000));
		assertEquals(-180_000, f.cycle(-300_000));
	}

	@Test
	public void measuredBatteryPowerIsRemovedFromBase() throws Exception {
		var f = new Fixture();
		for (int measuredEss : new int[] { -100_000, 0, 100_000 }) {
			f.ess.withActivePower(measuredEss);
			f.meter.withActivePower(200_000 - measuredEss);
			assertEquals(-180_000, f.cycle(-300_000));
		}
	}

	// ----------------------------------------------- together with balancing

	@Test
	public void balancingRunsFreelyAboveReserve() throws Exception {
		var f = new Fixture();
		var balancing = f.createBalancing();
		f.controller.run();
		balancing.run();
		// Balancing works through the PID filter, so only the direction is fixed
		assertTrue("Above the reserve, balancing must be free to discharge", f.solve() > 0);
	}

	@Test
	public void balancingCannotDrainTheReserve() throws Exception {
		var f = new Fixture();
		var balancing = f.createBalancing();
		f.ess.withSoc(20);
		f.controller.run();
		balancing.run();
		assertEquals(0, f.solve());
	}

	// ------------------------------------------------------ limits and inputs

	@Test
	public void respectsHardwareLimitsAndDebouncesUnfulfillableLimit() throws Exception {
		var f = new Fixture();
		f.ess.withAllowedDischargePower(20_000);
		f.meter.withActivePower(500_000);
		for (var cycle = 1; cycle <= 5; cycle++) {
			assertEquals(20_000, f.cycle(0));
			assertEquals(cycle == 5, f.state(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE));
		}
		f.meter.withActivePower(200_000);
		assertEquals(-100_000, f.cycle(-100_000));
		assertFalse(f.state(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE));
	}

	@Test
	public void precedingProtectionKeepsPriority() throws Exception {
		var f = new Fixture();
		f.meter.withActivePower(500_000);
		for (var cycle = 0; cycle < 5; cycle++) {
			f.ess.setActivePowerLessOrEquals(10_000);
			assertEquals(10_000, f.cycle(100_000));
		}
		assertTrue(f.state(ControllerEssPeakShavingReserve.ChannelId.LIMIT_UNFULFILLABLE));
	}

	@Test
	public void missingMeasurementsSetWarningWithoutConstraints() throws Exception {
		for (var missing = 0; missing < 3; missing++) {
			var f = new Fixture();
			switch (missing) {
			case 0 -> f.meter.withActivePower(null);
			case 1 -> f.ess.withActivePower(null);
			case 2 -> f.ess.withSoc(null);
			}
			try {
				f.controller.run();
				fail("Missing telemetry must not be treated as zero");
			} catch (InvalidValueException expected) {
				assertTrue(f.state(ControllerEssPeakShavingReserve.ChannelId.INPUT_UNAVAILABLE));
			}
			assertEquals(-1_000_000, f.power.getMinPower(f.ess, ALL, ACTIVE));
			assertEquals(1_000_000, f.power.getMaxPower(f.ess, ALL, ACTIVE));
		}
	}

	@Test
	public void islandAndUndefinedGridModeDoNotApplyConstraints() throws Exception {
		var f = new Fixture();
		f.ess.withSoc(10);
		f.ess.withGridMode(GridMode.OFF_GRID);
		assertEquals(300_000, f.cycle(300_000));
		assertFalse(f.state(ControllerEssPeakShavingReserve.ChannelId.INPUT_UNAVAILABLE));
		f.ess.withGridMode(GridMode.UNDEFINED);
		assertEquals(300_000, f.cycle(300_000));
		assertTrue(f.state(ControllerEssPeakShavingReserve.ChannelId.INPUT_UNAVAILABLE));
	}

	@Test
	public void rejectsInvalidReserve() throws Exception {
		for (int reserve : new int[] { -1, 101 }) {
			try {
				new Fixture(config().setReserveSoc(reserve));
				fail("Invalid reserve must be rejected");
			} catch (RuntimeException e) {
				Throwable cause = e;
				while (cause.getCause() != null) {
					cause = cause.getCause();
				}
				assertTrue(cause instanceof IllegalArgumentException);
			}
		}
	}

	private static void assertSettled(String label, int[] grid, int target, int tolerance) {
		// Last 20 cycles: close to the target and no remaining oscillation
		for (var k = grid.length - 20; k < grid.length; k++) {
			assertTrue(label + ": grid " + grid[k] + " W in cycle " + k + " not within " + tolerance
					+ " W of " + target, Math.abs(grid[k] - target) <= tolerance);
		}
	}

	private static MyConfig.Builder config() {
		return MyConfig.create().setId("ctrl0").setEssId("ess0").setMeterId("meter0") //
				.setPeakShavingPower(380_000).setReserveSoc(20);
	}

	private static class Fixture {
		private final EssPowerImpl power = new EssPowerImpl();
		private final AtomicReference<SymmetricApplyPowerRecord> applied = new AtomicReference<>();
		private final DummyManagedSymmetricEss ess = new DummyManagedSymmetricEss("ess0") //
				.setPower(this.power) //
				.withAllowedChargePower(-1_000_000).withAllowedDischargePower(1_000_000) //
				.withMaxApparentPower(1_000_000) //
				.withSoc(50).withGridMode(GridMode.ON_GRID).withActivePower(0) //
				.withSymmetricApplyPowerCallback(this.applied::set);
		private final DummyElectricityMeter meter = new DummyElectricityMeter("meter0").withActivePower(200_000);
		private final ControllerEssPeakShavingReserveImpl controller = new ControllerEssPeakShavingReserveImpl();
		private final ComponentTest powerTest;

		private Fixture() throws Exception {
			this(config());
		}

		private Fixture(MyConfig.Builder config) throws Exception {
			var cm = new DummyConfigurationAdmin();
			cm.getOrCreateEmptyConfiguration(EssPower.SINGLETON_SERVICE_PID);
			this.powerTest = new ComponentTest(this.power) //
					.addReference("cm", cm).addReference("addEss", this.ess) //
					.activate(new PeakShavingReservePowerConfig());
			new ControllerTest(this.controller) //
					.addReference("componentManager", new DummyComponentManager()) //
					.addComponent(this.ess).addComponent(this.meter) //
					.activate(config.build());
		}

		/** Run the controller, then a subsequent controller asking for externalPower. */
		private int cycle(int externalPower) throws Exception {
			this.controller.run();
			this.ess.setActivePowerEquals(externalPower);
			return this.solve();
		}

		private int solve() throws Exception {
			this.applied.set(null);
			this.powerTest.next(new TestCase());
			assertNotNull("Solver must actually call the ESS applyPower method", this.applied.get());
			return this.applied.get().activePower();
		}

		private boolean state(ControllerEssPeakShavingReserve.ChannelId channelId) {
			TestUtils.activateNextProcessImage(this.controller);
			return Boolean.TRUE.equals(this.controller.channel(channelId).value().get());
		}

		/**
		 * Closed loop: the battery follows the setpoint at once, its measurement
		 * arrives {@code delay} cycles later - as with an ESS behind Edge2Edge. The
		 * grid meter always shows the actual value.
		 *
		 * @return actual grid power per cycle
		 */
		private int[] simulate(int load, int soc, int cycles, int delay, boolean withBalancing) throws Exception {
			var balancing = withBalancing ? this.createBalancing() : null;
			this.ess.withSoc(soc);
			var applied = new int[cycles + delay + 1];
			var grid = new int[cycles];
			for (var k = 0; k < cycles; k++) {
				var actual = applied[k + delay];
				var measured = applied[k];
				this.ess.withActivePower(measured);
				this.meter.withActivePower(load - actual);
				this.controller.run();
				if (balancing != null) {
					balancing.run();
				}
				applied[k + delay + 1] = this.solve();
				grid[k] = load - applied[k + delay + 1];
			}
			return grid;
		}

		private ControllerEssBalancingImpl createBalancing() throws Exception {
			var balancing = new ControllerEssBalancingImpl();
			new ControllerTest(balancing) //
					.addReference("cm", new DummyConfigurationAdmin()) //
					.addReference("ess", this.ess).addReference("meter", this.meter) //
					.activate(new PeakShavingReserveBalancingConfig());
			return balancing;
		}
	}
}
