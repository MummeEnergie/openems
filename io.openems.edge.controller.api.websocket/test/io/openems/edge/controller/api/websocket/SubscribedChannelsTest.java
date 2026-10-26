package io.openems.edge.controller.api.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.google.gson.JsonNull;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.request.SubscribeChannelsRequest;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.test.DummyComponentManager;

public class SubscribedChannelsTest {

	private static SubscribeChannelsRequest subscribe(int count, String... channels)
			throws OpenemsNamedException {
		var array = JsonUtils.buildJsonArray();
		for (var channel : channels) {
			array.add(channel);
		}
		return SubscribeChannelsRequest.from(new GenericJsonrpcRequest(SubscribeChannelsRequest.METHOD,
				JsonUtils.buildJsonObject() //
						.addProperty("count", count) //
						.add("channels", array.build()) //
						.build()));
	}

	@Test
	public void testUnreadableChannelIsRememberedOncePerSubscription() throws OpenemsNamedException {
		var componentManager = new DummyComponentManager() //
				.addComponent(new DummySum());
		var sut = new WsData.SubscribedChannels();
		sut.handleSubscribeChannelsRequest(subscribe(1, "_sum/GridActivePower", "evcs0/Plug"));

		var values = sut.getChannelValues(componentManager);
		assertEquals(JsonNull.INSTANCE, values.get("evcs0/Plug"));
		assertTrue(values.containsKey("_sum/GridActivePower"));
		assertEquals(Set.of(new ChannelAddress("evcs0", "Plug")), sut.getUnreadableChannels());

		// Second cycle: still null, still remembered (and therefore not logged again)
		values = sut.getChannelValues(componentManager);
		assertEquals(JsonNull.INSTANCE, values.get("evcs0/Plug"));
		assertEquals(1, sut.getUnreadableChannels().size());

		// A new subscription starts over
		sut.handleSubscribeChannelsRequest(subscribe(2, "_sum/GridActivePower"));
		assertTrue(sut.getUnreadableChannels().isEmpty());
		sut.getChannelValues(componentManager);
		assertTrue(sut.getUnreadableChannels().isEmpty());
	}

}
