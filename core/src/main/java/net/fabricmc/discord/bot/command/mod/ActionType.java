/*
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.discord.bot.command.mod;

import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.util.FormatUtil;

public interface ActionType {
	Kind getKind();
	String getId();
	boolean hasDuration();
	boolean hasDeactivation();
	boolean isNotificationBarrier();

	default boolean canRevertBeyondBotDb() {
		return true;
	}

	ActivateResult activate(Server server, long targetId, boolean isDirect, int data, String reason, DiscordBot bot) throws DiscordException;
	void deactivate(Server server, long targetId, Integer resetData, String reason, DiscordBot bot) throws DiscordException;
	boolean isActive(Server server, long targetId, int data, DiscordBot bot);

	/**
	 * Compare two data values to determine precedence.
	 *
	 * @param dataA
	 * @param dataB
	 * @return 0 for equal, <0 if b is higher/supersedes a, >0 if a is higher/supersedes b
	 */
	default int compareData(int dataA, int dataB) {
		return 0;
	}

	/**
	 * Determine whether the data value is applicable considering the previous reset data.
	 */
	default boolean checkData(int data, int prevResetData) {
		return true;
	}

	static ActionType get(String kindId, String typeId) {
		for (Kind kind : Kind.values()) {
			if (kind.id.equals(kindId)) {
				for (ActionType type : kind.values) {
					if (type.getId().equals(typeId)) return type;
				}

				throw new IllegalArgumentException("invalid "+kindId+" type: "+typeId);
			}
		}

		throw new IllegalArgumentException("invalid kind: "+kindId);
	}

	public enum Kind {
		CHANNEL("channel", false, ChannelActionType.values()),
		USER("user", true, UserActionType.values());

		public final String id;
		public final String capitalized;
		public final boolean useEncodedTargetId;
		final ActionType[] values;

		Kind(String id, boolean encodeTargetId, ActionType[] values) {
			this.id = id;
			this.capitalized = FormatUtil.capitalize(id);
			this.useEncodedTargetId = encodeTargetId;
			this.values = values;
		}
	}

	public record ActivateResult(boolean applicable, int targets, Integer resetData) { }
}
