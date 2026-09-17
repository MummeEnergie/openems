package io.openems.edge.controller.symmetric.peakshaving;

import static io.openems.common.utils.ReflectionUtils.getValueViaReflection;
import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import io.openems.common.exceptions.InvalidValueException;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.controller.api.common.ApiWorker;
import io.openems.edge.controller.api.common.WritePojo;
import io.openems.edge.controller.ess.balancing.ControllerEssBalancingImpl;
import io.openems.edge.controller.ess.balancing.PeakShavingBalancingConfig;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.core.power.EssPower;
import io.openems.edge.ess.core.power.EssPowerImpl;
import io.openems.edge.ess.core.power.PeakShavingPowerConfig;
import io.openems.edge.ess.test.AbstractDummyManagedSymmetricEss.SymmetricApplyPowerRecord;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.meter.test.DummyElectricityMeter;

public class LimitOnlyTest {

	@Test
	public void allowsExternalChargingBelowGridLimit() throws Exception {
		var f = new Fixture();
		assertEquals(-100_000, f.cycle(-100_000));
	}

	@Test
	public void limitsAdditionalChargingBelowGridLimit() throws Exception {
		var f = new Fixture();
		assertEquals(-250_000, f.cycle(-300_000));
	}

	@Test
	public void requiresDischargingAboveGridLimit() throws Exception {
		var f = new Fixture();
		f.meter.withActivePower(500_000);
		assertEquals(50_000, f.cycle(0));
	}

	@Test
	public void permitsDischargingBeyondRequiredMinimum() throws Exception {
		var f = new Fixture();
		f.meter.withActivePower(500_000);
		assertEquals(100_000, f.cycle(100_000));
	}

	@Test
	public void removesMeasuredBatteryContributionAndHandlesPvSurplus() throws Exception {
		var f = new Fixture();
		for (int measuredEss : new int[] { -100_000, 0, 100_000 }) {
			f.ess.withActivePower(measuredEss);
			f.meter.withActivePower(200_000 - measuredEss);
			assertEquals(-250_000, f.cycle(-300_000));
			f.meter.withActivePower(-100_000 - measuredEss);
			assertEquals(-550_000, f.cycle(-600_000));
		}
	}

	@Test
	public void recomputesLimitEachCycleAndIgnoresRechargeThreshold() throws Exception {
		var f = new Fixture(config().setRechargePower(400_000));
		assertEquals(-100_000, f.cycle(-100_000));
		f.meter.withActivePower(500_000);
		assertEquals(50_000, f.cycle(-100_000));
		f.meter.withActivePower(200_000);
		assertEquals(-100_000, f.cycle(-100_000));
	}

	@Test
	public void doesNotConstrainReactivePower() throws Exception {
		var f = new Fixture();
		f.controller.run();
		assertFalse(f.ess.getSetReactivePowerEqualsChannel().getNextWriteValue().isPresent());
		f.ess.setReactivePowerEquals(75_000);
		f.ess.setActivePowerEquals(-100_000);
		f.solve();
		assertEquals(75_000, f.applied.get().reactivePower());
	}

	@Test
	public void respectsHardwareLimitsAndDebouncesUnfulfillableLimit() throws Exception {
		var f = new Fixture();
		f.ess.withAllowedChargePower(-80_000).withAllowedDischargePower(20_000);
		assertEquals(-80_000, f.cycle(-100_000));
		f.meter.withActivePower(500_000);
		for (var cycle = 1; cycle <= 5; cycle++) {
			assertEquals(20_000, f.cycle(0));
			assertEquals(cycle == 5, f.state(ControllerEssPeakShaving.ChannelId.LIMIT_UNFULFILLABLE));
		}
		f.meter.withActivePower(200_000);
		assertEquals(-80_000, f.cycle(-100_000));
		assertFalse(f.state(ControllerEssPeakShaving.ChannelId.LIMIT_UNFULFILLABLE));
	}

	@Test
	public void precedingProtectionOverridesGridLimit() throws Exception {
		var f = new Fixture();
		f.meter.withActivePower(500_000);
		for (var cycle = 0; cycle < 5; cycle++) {
			f.ess.setActivePowerLessOrEquals(10_000);
			assertEquals(10_000, f.cycle(100_000));
		}
		assertTrue(f.state(ControllerEssPeakShaving.ChannelId.LIMIT_UNFULFILLABLE));
	}

	@Test
	public void lowSocAllowsChargingButProhibitsDischarging() throws Exception {
		var f = new Fixture(config().setSocInfimum(20).setSocSupremum(80));
		for (int soc : new int[] { 10, 20 }) {
			f.ess.withSoc(soc);
			assertEquals(-100_000, f.cycle(-100_000));
			assertEquals(0, f.cycle(100_000));
		}
		f.meter.withActivePower(500_000);
		for (var cycle = 0; cycle < 5; cycle++) {
			assertEquals(0, f.cycle(100_000));
		}
		assertTrue(f.state(ControllerEssPeakShaving.ChannelId.LIMIT_UNFULFILLABLE));
	}

	@Test
	public void highSocAllowsDischargingButProhibitsCharging() throws Exception {
		var f = new Fixture(config().setSocInfimum(20).setSocSupremum(80));
		for (int soc : new int[] { 80, 90 }) {
			f.ess.withSoc(soc);
			assertEquals(100_000, f.cycle(100_000));
			assertEquals(0, f.cycle(-100_000));
		}
		f.meter.withActivePower(500_000);
		assertEquals(50_000, f.cycle(0));
	}

	@Test
	public void precedingForcedChargeKeepsPriorityOverSocAndGridLimits() throws Exception {
		var f = new Fixture(config().setSocSupremum(80));
		f.ess.withSoc(90);
		f.meter.withActivePower(500_000);
		f.ess.setActivePowerLessOrEquals(-10_000);
		assertEquals(-10_000, f.cycle(100_000));
	}

	@Test
	public void missingMeasurementsSetWarningWithoutFabricatingPower() throws Exception {
		for (var missing = 0; missing < 3; missing++) {
			var f = new Fixture();
			assertEquals(-250_000, f.cycle(-300_000));
			switch (missing) {
			case 0 -> f.meter.withActivePower(null);
			case 1 -> f.ess.withActivePower(null);
			case 2 -> f.ess.withSoc(null);
			}
			try {
				f.controller.run();
				fail("Missing telemetry must not be treated as zero");
			} catch (InvalidValueException expected) {
				assertTrue(f.state(ControllerEssPeakShaving.ChannelId.INPUT_UNAVAILABLE));
			}
			assertEquals(-1_000_000, f.power.getMinPower(f.ess, ALL, ACTIVE));
			assertEquals(1_000_000, f.power.getMaxPower(f.ess, ALL, ACTIVE));
			f.meter.withActivePower(200_000);
			f.ess.withActivePower(0).withSoc(50);
			assertEquals(-250_000, f.cycle(-300_000));
			assertFalse(f.state(ControllerEssPeakShaving.ChannelId.INPUT_UNAVAILABLE));
		}
	}

	@Test
	public void islandAndUndefinedGridModeDoNotApplyConstraints() throws Exception {
		var f = new Fixture();
		f.ess.withGridMode(GridMode.OFF_GRID);
		assertEquals(-300_000, f.cycle(-300_000));
		assertFalse(f.state(ControllerEssPeakShaving.ChannelId.INPUT_UNAVAILABLE));
		f.ess.withGridMode(GridMode.UNDEFINED);
		assertEquals(-300_000, f.cycle(-300_000));
		assertTrue(f.state(ControllerEssPeakShaving.ChannelId.INPUT_UNAVAILABLE));
	}

	@Test
	public void apiTimeoutEnablesFallbackAndRenewedRequestTakesPriority() throws Exception {
		var f = new Fixture();
		var fallback = f.createFallback();
		var api = new ApiWorker(f.controller);
		try {
			api.setTimeoutSeconds(60);
			api.addValue(f.ess.getSetActivePowerEqualsChannel(), new WritePojo(-300_000));
			f.controller.run();
			api.run();
			fallback.run();
			assertEquals(-250_000, f.solve());

			// Wait for the actual timeout task to finish, not an estimated sleep interval.
			api.setTimeoutSeconds(1);
			ScheduledFuture<?> timeout = getValueViaReflection(api, "future");
			timeout.get(5, TimeUnit.SECONDS);
			f.meter.withActivePower(500_000);
			f.controller.run();
			api.run();
			assertEquals(50_000, f.power.getMinPower(f.ess, ALL, ACTIVE));
			assertEquals(1_000_000, f.power.getMaxPower(f.ess, ALL, ACTIVE));
			fallback.run();
			var fallbackPower = f.power.getMinPower(f.ess, ALL, ACTIVE);
			assertTrue("Fallback must select its own operating point above the grid-limit minimum", fallbackPower > 50_000);
			assertEquals(fallbackPower, f.power.getMaxPower(f.ess, ALL, ACTIVE));
			assertEquals(fallbackPower, f.solve());

			api.setTimeoutSeconds(60);
			api.addValue(f.ess.getSetActivePowerEqualsChannel(), new WritePojo(100_000));
			f.controller.run();
			api.run();
			fallback.run();
			assertEquals(100_000, f.solve());
		} finally {
			shutdown(api);
		}
	}

	@Test
	public void separateWorkerWritesDoNotExtendBatteryTimeout() throws Exception {
		var f = new Fixture();
		var batteryApi = new ApiWorker(f.controller);
		var otherApi = new ApiWorker(f.controller);
		try {
			batteryApi.setTimeoutSeconds(1);
			batteryApi.addValue(f.ess.getSetActivePowerEqualsChannel(), new WritePojo(-100_000));
			ScheduledFuture<?> batteryTimeout = getValueViaReflection(batteryApi, "future");
			otherApi.setTimeoutSeconds(0);
			otherApi.addValue(f.ess.getSetReactivePowerEqualsChannel(), new WritePojo(20_000));
			batteryTimeout.get(5, TimeUnit.SECONDS);
			f.controller.run();
			batteryApi.run();
			otherApi.run();
			assertEquals(-250_000, f.power.getMinPower(f.ess, ALL, ACTIVE));
			assertTrue(f.power.getMaxPower(f.ess, ALL, ACTIVE) > 50_000);
			f.ess.setActivePowerEquals(50_000);
			assertEquals(50_000, f.solve());
			assertEquals(20_000, f.applied.get().reactivePower());
		} finally {
			shutdown(batteryApi);
			shutdown(otherApi);
		}
	}

	@Test
	public void apparentPowerLimitAlsoBoundsPeakShaving() throws Exception {
		var f = new Fixture();
		f.ess.withMaxApparentPower(30_000);
		f.meter.withActivePower(500_000);
		assertEquals(30_000, f.cycle(100_000));
	}

	@Test
	public void standardModeRetainsZeroTargetRechargeAndSocWindow() throws Exception {
		var f = new Fixture(config().setLimitOnly(false));
		f.controller.run();
		assertEquals(0, f.solve());
		assertEquals(0, f.applied.get().reactivePower());

		var recharge = new Fixture(config().setLimitOnly(false).setRechargePower(250_000));
		recharge.controller.run();
		assertEquals(-15_000, recharge.solve());

		var outsideSoc = new Fixture(config().setLimitOnly(false).setSocInfimum(20).setSocSupremum(80));
		for (int soc : new int[] { 10, 90 }) {
			outsideSoc.ess.withSoc(soc);
			assertEquals(-100_000, outsideSoc.cycle(-100_000));
		}
	}

	@Test
	public void rejectsInvalidSocWindowsInLimitOnlyMode() throws Exception {
		for (int[] window : new int[][] { { -1, 100 }, { 0, 101 }, { 80, 20 }, { 50, 50 } }) {
			try {
				new Fixture(config().setSocInfimum(window[0]).setSocSupremum(window[1]));
				fail("Invalid SoC window must be rejected");
			} catch (RuntimeException e) {
				Throwable cause = e;
				while (cause.getCause() != null) {
					cause = cause.getCause();
				}
				assertTrue(cause instanceof IllegalArgumentException);
				assertTrue(cause.getMessage().contains("socInfimum < socSupremum"));
			}
		}
	}

	private static void shutdown(ApiWorker api) {
		// ApiWorker currently exposes no shutdown method; do not leak its executor in tests.
		ScheduledExecutorService executor = getValueViaReflection(api, "executor");
		executor.shutdownNow();
	}

	private static MyConfig.Builder config() {
		return MyConfig.create().setId("ctrl0").setEssId("ess0").setMeterId("meter0") //
				.setPeakShavingPower(450_000).setRechargePower(0).setLimitOnly(true);
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
		private final ControllerEssPeakShavingImpl controller = new ControllerEssPeakShavingImpl();
		private final ComponentTest powerTest;

		private Fixture() throws Exception {
			this(config());
		}

		private Fixture(MyConfig.Builder config) throws Exception {
			var cm = new DummyConfigurationAdmin();
			cm.getOrCreateEmptyConfiguration(EssPower.SINGLETON_SERVICE_PID);
			this.powerTest = new ComponentTest(this.power) //
					.addReference("cm", cm).addReference("addEss", this.ess) //
					.activate(new PeakShavingPowerConfig());
			new ControllerTest(this.controller) //
					.addReference("componentManager", new DummyComponentManager()) //
					.addComponent(this.ess).addComponent(this.meter) //
					.activate(config.build());
		}

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

		private boolean state(ControllerEssPeakShaving.ChannelId channelId) {
			TestUtils.activateNextProcessImage(this.controller);
			return Boolean.TRUE.equals(this.controller.channel(channelId).value().get());
		}

		private ControllerEssBalancingImpl createFallback() throws Exception {
			var fallback = new ControllerEssBalancingImpl();
			new ControllerTest(fallback) //
					.addReference("cm", new DummyConfigurationAdmin()) //
					.addReference("ess", this.ess).addReference("meter", this.meter) //
					.activate(new PeakShavingBalancingConfig());
			return fallback;
		}
	}
}
