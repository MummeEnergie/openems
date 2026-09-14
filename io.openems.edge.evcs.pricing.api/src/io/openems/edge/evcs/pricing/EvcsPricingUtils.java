package io.openems.edge.evcs.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.OptionalDouble;

import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

/**
 * Shared utilities for EVCS pricing controllers.
 */
public final class EvcsPricingUtils {

	private EvcsPricingUtils() {
	}

	/**
	 * Rounds a price to 4 decimal places using HALF_UP rounding.
	 *
	 * @param price the price value to round
	 * @return the rounded price
	 */
	public static double roundPrice(double price) {
		return BigDecimal.valueOf(price)
				.setScale(4, RoundingMode.HALF_UP)
				.doubleValue();
	}

	/**
	 * Determines the upper bound of the lookahead window.
	 *
	 * <p>
	 * Uses the next price-change timestamp from {@link EvcsPricing} when available,
	 * but always guarantees at least one 15-minute quarter forward so that
	 * {@code TimeOfUsePrices#getBetweenExclusive} returns a non-empty stream.
	 *
	 * @param nowRounded  the current time truncated to the nearest quarter-hour
	 * @param evcsPricing the pricing core to query for the next price-change timestamp
	 * @return the next tick timestamp to use as the lookahead window upper bound
	 */
	public static ZonedDateTime resolveNextTick(ZonedDateTime nowRounded, EvcsPricing evcsPricing) {
		var nextPriceChangeMs = evcsPricing.getNextPriceChange().orElse(null);
		var minimumNextTick = nowRounded.plusMinutes(15);

		if (nextPriceChangeMs == null) {
			return minimumNextTick;
		}

		var nextTick = Instant.ofEpochMilli(nextPriceChangeMs).atZone(nowRounded.getZone());
		return nextTick.isAfter(minimumNextTick) ? nextTick : minimumNextTick;
	}

	/**
	 * Computes the average electricity price over the lookahead window.
	 *
	 * <p>
	 * TimeOfUseTariff prices are in Currency/MWh; this method divides by 10 to
	 * return ct/kWh.
	 *
	 * @param tariff      the time-of-use tariff provider; returns empty if null
	 * @param nowRounded  current time truncated to quarter-hour
	 * @param evcsPricing the pricing core used to resolve the next tick
	 * @return average price in ct/kWh, or empty if no prices are available
	 */
	public static OptionalDouble computeAverageCtKwh(TimeOfUseTariff tariff, ZonedDateTime nowRounded,
			EvcsPricing evcsPricing) {
		if (tariff == null) {
			return OptionalDouble.empty();
		}
		var prices = tariff.getPrices();
		if (prices.isEmpty()) {
			return OptionalDouble.empty();
		}
		var nextTick = resolveNextTick(nowRounded, evcsPricing);
		var avgOpt = prices.getBetweenExclusive(nowRounded, nextTick)
				.mapToDouble(entry -> entry.getValue())
				.average();
		if (avgOpt.isEmpty()) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(avgOpt.getAsDouble() / 10.0);
	}

	/**
	 * Linearly interpolates a price between {@code maxPrice} and {@code minPrice}.
	 *
	 * <p>
	 * When {@code avg == threshold} the ratio is 0 and {@code maxPrice} is
	 * returned. When {@code avg >= upperBound} the ratio is clamped to 1.0 and
	 * {@code minPrice} is returned.
	 *
	 * @param avg        the current averaged value (e.g. PV watts or SoC %)
	 * @param threshold  the lower bound at which interpolation begins
	 * @param upperBound the upper bound at which the minimum price is reached
	 * @param maxPrice   the price returned when {@code avg == threshold}
	 * @param minPrice   the price returned when {@code avg >= upperBound}
	 * @return linearly interpolated price
	 */
	public static double linearInterpolate(double avg, double threshold, double upperBound, double maxPrice,
			double minPrice) {
		var range = upperBound - threshold;
		var ratio = Math.min(1.0, (avg - threshold) / range);
		return maxPrice - ratio * (maxPrice - minPrice);
	}
}
