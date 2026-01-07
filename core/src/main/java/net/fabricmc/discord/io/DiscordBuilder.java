/*
 * Copyright (c) 2026 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.discord.io;

import java.util.BitSet;
import java.util.ServiceLoader;

public class DiscordBuilder {
	private final DiscordConfig config = new DiscordConfig();

	public static DiscordBuilder create(String accessToken, GlobalEventHolder globalEventHolder) {
		return new DiscordBuilder(accessToken, globalEventHolder);
	}

	private DiscordBuilder(String accessToken, GlobalEventHolder globalEventHolder) {
		config.accessToken = accessToken;
		config.globalEventHolder = globalEventHolder;
	}

	public DiscordBuilder intents(Intent... intents) {
		BitSet set = new BitSet();

		for (Intent intent : intents) {
			set.set(intent.pow2);
		}

		config.intents = set;

		return this;
	}

	public DiscordBuilder cacheUsers(boolean value) {
		config.cacheUsers = value;

		return this;
	}

	public DiscordBuilder globalEventHolder(GlobalEventHolder holder) {
		config.globalEventHolder = holder;

		return this;
	}

	public Discord build() {
		DiscordProvider provider = ServiceLoader.load(DiscordProvider.class).findFirst().orElse(null);

		if (provider == null) {
			throw new RuntimeException("no discord provider on the class path");
		}

		return provider.create(config);
	}

	public class DiscordConfig {
		public String accessToken;
		public BitSet intents;
		public boolean cacheUsers;
		public GlobalEventHolder globalEventHolder;
	}

	// https://discord.com/developers/docs/events/gateway#gateway-intents
	public enum Intent {
		GUILDS(0),
		GUILD_MEMBERS(1),
		GUILD_MODERATION(2),
		GUILD_EXPRESSIONS(3),
		GUILD_INTEGRATIONS(4),
		GUILD_WEBHOOKS(5),
		GUILD_INVITES(6),
		GUILD_VOICE_STATES(7),
		GUILD_PRESENCES(8),
		GUILD_MESSAGES(9),
		GUILD_MESSAGE_REACTIONS(10),
		GUILD_MESSAGE_TYPING(11),
		DIRECT_MESSAGES(12),
		DIRECT_MESSAGE_REACTIONS(13),
		DIRECT_MESSAGE_TYPING(14),
		MESSAGE_CONTENT(15),
		GUILD_SCHEDULED_EVENTS(16),
		AUTO_MODERATION_CONFIGURATION(20),
		AUTO_MODERATION_EXECUTION(21),
		GUILD_MESSAGE_POLLS(24),
		DIRECT_MESSAGE_POLLS(25);

		public int pow2;

		Intent(int pow2) {
			this.pow2 = pow2;
		}
	}
}
