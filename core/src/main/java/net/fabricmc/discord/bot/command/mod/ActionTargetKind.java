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

public enum ActionTargetKind {
	CHANNEL("channel"),
	USER("user");

	public final String id;

	public static ActionTargetKind get(String id) {
		for (ActionTargetKind type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}

		throw new IllegalArgumentException("invalid kind: "+id);
	}

	ActionTargetKind(String id) {
		this.id = id;
	}
}
