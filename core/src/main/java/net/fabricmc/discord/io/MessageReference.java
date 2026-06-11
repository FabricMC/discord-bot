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

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

public interface MessageReference {
	Discord getDiscord();

	long getServerId();
	@Nullable Server getServer();

	long getChannelId();
	@Nullable Channel getChannel();

	long getMessageId();
	@Nullable Message getMessage(boolean request);

	Type getType();

	public enum Type {
		REPLY(0), // DEFAULT
		FORWARD(1), // FORWARD
		OTHER(-1);

		public static final Type[] VALUES = values();
		private static final Type[] INDEX;

		public final int id;

		Type(int id) {
			this.id = id;
		}

		public static Type get(int id) {
			if (id >= 0 && id < INDEX.length) {
				return INDEX[id];
			} else {
				return OTHER;
			}
		}

		static {
			INDEX = new Type[VALUES[VALUES.length - 2].id + 2];
			Arrays.fill(INDEX, OTHER);

			for (Type t : VALUES) {
				if (t.id  >= 0) INDEX[t.id] = t;
			}
		}
	}

	static MessageReference create(Type type, long serverId, long channelId, long messageId) {
		return new BuiltMessageReference(type, serverId, channelId, messageId);
	}

	static class BuiltMessageReference implements MessageReference {
		private final Type type;
		private final long serverId;
		private final long channelId;
		private final long messageId;

		BuiltMessageReference(Type type, long serverId, long channelId, long messageId) {
			this.type = type;
			this.serverId = serverId;
			this.channelId = channelId;
			this.messageId = messageId;
		}

		@Override
		public Discord getDiscord() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getServerId() {
			return serverId;
		}

		@Override
		public @Nullable Server getServer() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getChannelId() {
			return channelId;
		}

		@Override
		public @Nullable Channel getChannel() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getMessageId() {
			return messageId;
		}

		@Override
		public @Nullable Message getMessage(boolean request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Type getType() {
			return type;
		}
	}
}
