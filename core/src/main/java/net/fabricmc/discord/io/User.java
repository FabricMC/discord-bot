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

import java.util.Collection;

public interface User {
	Discord getDiscord();
	long getId();
	String getName();
	String getDiscriminator();
	String getGlobalNickname(); // aka global name, server-independent nickname

	default String getGlobalDisplayName() {
		String ret = getGlobalNickname();

		return ret != null ? ret : getName();
	}

	default String getMentionTag() {
		return getMentionTag(getId());
	}

	static String getMentionTag(long id) {
		return String.format("<@%d>", id);
	}

	default String getNickMentionTag() {
		return getNickMentionTag(getId());
	}

	static String getNickMentionTag(long id) {
		return String.format("<@!%d>", id);
	}

	boolean isBot();
	boolean isYourself();
	Collection<Server> getMutualServers();

	Channel dm();
}
