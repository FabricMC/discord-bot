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

package net.fabricmc.discord.ioimpl.jda;

import java.util.Objects;

import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageReference;
import net.fabricmc.discord.io.Server;

public class MessageReferenceImpl implements MessageReference {
	private final net.dv8tion.jda.api.entities.MessageReference wrapped;
	private final DiscordImpl discord;

	MessageReferenceImpl(net.dv8tion.jda.api.entities.MessageReference wrapped, DiscordImpl discord) {
		Objects.requireNonNull(wrapped, "null wrapped");
		Objects.requireNonNull(discord, "null discord");

		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public long getServerId() {
		return wrapped.getGuildIdLong();
	}

	@Override
	public Server getServer() {
		return ServerImpl.wrap(wrapped.getGuild(), discord);
	}

	@Override
	public long getChannelId() {
		return wrapped.getChannelIdLong();
	}

	@Override
	public ChannelImpl getChannel() {
		return ChannelImpl.wrap(wrapped.getChannel(), discord);
	}

	@Override
	public long getMessageId() {
		return wrapped.getMessageIdLong();
	}

	@Override
	public Message getMessage(boolean request) {
		return MessageImpl.wrap(request ? wrapped.resolve().complete() : wrapped.getMessage(), discord, getChannel());
	}

	@Override
	public Type getType() {
		return Type.get(wrapped.getType().getId());
	}

	@Override
	public String toString() {
		return wrapped.toString();
	}

	static MessageReferenceImpl wrap(net.dv8tion.jda.api.entities.MessageReference ref, DiscordImpl discord) {
		if (ref == null) return null;

		return new MessageReferenceImpl(ref, discord);
	}

	net.dv8tion.jda.api.entities.MessageReference unwrap() {
		return wrapped;
	}
}
