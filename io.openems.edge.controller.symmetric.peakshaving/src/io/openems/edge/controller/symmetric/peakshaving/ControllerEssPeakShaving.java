package io.openems.edge.controller.symmetric.peakshaving;

import io.openems.common.channel.Debounce;
import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerEssPeakShaving extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		INPUT_UNAVAILABLE(Doc.of(Level.WARNING) //
				.text("Grid limiting unavailable: missing measurement or undefined grid mode")), //
		LIMIT_UNFULFILLABLE(Doc.of(Level.WARNING) //
				.debounce(4, Debounce.TRUE_VALUES_IN_A_ROW_TO_SET_TRUE) // Four suppressed updates; warn on the fifth.
				.text("Grid limit requires more ESS power than permitted by preceding constraints"));
		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

}
