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

package net.fabricmc.discord.ioimpl.javacord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.javacord.api.entity.channel.PrivateChannel;
import org.javacord.api.entity.channel.RegularServerChannel;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.MessageSet;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.Permissions;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.User;

public class ChannelImpl implements Channel {
	private final org.javacord.api.entity.channel.Channel wrapped;
	private final DiscordImpl discord;
	private final ServerImpl server;
	private final UserImpl user;

	ChannelImpl(org.javacord.api.entity.channel.Channel wrapped, DiscordImpl discord, ServerImpl server, UserImpl user) {
		this.wrapped = wrapped;
		this.discord = discord;
		this.server = server;
		this.user = user;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public ServerImpl getServer() {
		return server;
	}

	@Override
	public UserImpl getUser() {
		return user;
	}

	@Override
	public long getId() {
		return wrapped.getId();
	}

	@Override
	public Type getType() {
		return Type.fromId(wrapped.getType().getId());
	}

	@Override
	public String getName() {
		if (wrapped instanceof ServerChannel c) {
			return c.getName();
		} else {
			return null;
		}
	}

	@Override
	public Set<Permission> getPermissions(User user) {
		if (wrapped instanceof RegularServerChannel c) {
			return Permission.fromMask(c.getEffectivePermissions(((UserImpl) user).unwrap()).getAllowedBitmask());
		} else if (wrapped instanceof PrivateChannel) {
			return Permission.DM;
		} else {
			throw new IllegalStateException("not a suitable channel type");
		}
	}

	@Override
	public PermissionOverwriteData getPermissionOverwrites(Role role) {
		if (wrapped instanceof RegularServerChannel c) {
			Permissions res = c.getOverwrittenPermissions(((RoleImpl) role).unwrap());

			return new PermissionOverwriteData(Permission.fromMask(res.getAllowedBitmask()), Permission.fromMask(res.getDeniedBitmask()));
		} else {
			throw new IllegalStateException("not a suitable channel type");
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void setPermissionOverwrites(Role role, PermissionOverwriteData data, String reason) {
		if (wrapped instanceof RegularServerChannel c) {
			c.createUpdater()
			.addPermissionOverwrite(((RoleImpl) role).unwrap(), Permissions.fromBitmask(Permission.toMask(data.allowed()), Permission.toMask(data.denied())))
			.setAuditLogReason(reason)
			.update()
			.join();
		} else {
			throw new IllegalStateException("not a suitable channel type");
		}
	}

	@Override
	public Message getMessage(long id) {
		if (wrapped instanceof TextChannel channel) {
			return MessageImpl.wrap(channel.getMessageById(id).join(), this);
		} else {
			return null;
		}
	}

	@Override
	public List<MessageImpl> getMessages(int limit) {
		if (wrapped instanceof TextChannel channel) {
			MessageSet res = channel.getMessages(limit).join();
			List<MessageImpl> ret = new ArrayList<>(res.size());

			for (org.javacord.api.entity.message.Message msg : res) {
				ret.add(MessageImpl.wrap(msg, this));
			}

			return ret;
		} else {
			return Collections.emptyList();
		}
	}

	@Override
	public List<? extends Message> getMessagesBetween(long firstId, long lastId, int limit) {
		if (wrapped instanceof TextChannel channel) {
			if (limit < 0) limit = Integer.MAX_VALUE;
			List<Message> ret = new ArrayList<>();

			retrieveLoop: while (limit > 0) {
				MessageSet res = channel.getMessagesAfter(Math.min(100, limit), firstId).join();

				for (org.javacord.api.entity.message.Message msg : res) {
					if (msg.getId() >= lastId) break retrieveLoop;

					ret.add(MessageImpl.wrap(msg, this));
				}

				limit -= res.size();
			}

			return ret;
		} else {
			return Collections.emptyList();
		}
	}

	@Override
	public MessageImpl send(String message) {
		if (!(wrapped instanceof TextChannel c)) throw new IllegalArgumentException("not a text channel");

		return MessageImpl.wrap(c.sendMessage(message).join(), this);
	}

	@Override
	public Message send(MessageEmbed message) {
		if (!(wrapped instanceof TextChannel c)) throw new IllegalArgumentException("not a text channel");

		EmbedBuilder embed = MessageEmbedImpl.toBuilder(message);

		return MessageImpl.wrap(c.sendMessage(embed).join(), this);
	}

	@Override
	public Message send(Message message) {
		if (!(wrapped instanceof TextChannel c)) throw new IllegalArgumentException("not a text channel");

		MessageBuilder msg = MessageImpl.toBuilder(message);

		return MessageImpl.wrap(msg.send(c).join(), this);
	}

	@Override
	public void deleteMessage(long id, String reason) {
		if (!(wrapped instanceof TextChannel)) throw new IllegalArgumentException("not a text channel");

		org.javacord.api.entity.message.Message.delete(getDiscord().unwrap(), wrapped.getId(), id, reason).join();
	}

	@Override
	public void deleteMessages(long[] messageIds, String reason) {
		if (!(wrapped instanceof TextChannel c)) throw new IllegalArgumentException("not a text channel");

		c.deleteMessages(messageIds);
	}

	@Override
	public int getSlowmodeDelaySeconds() {
		if (!(wrapped instanceof ServerTextChannel c)) throw new IllegalArgumentException("not a text channel");

		return c.getSlowmodeDelayInSeconds();
	}

	@Override
	public void setSlowmodeDelaySeconds(int delaySec, String reason) {
		if (!(wrapped instanceof ServerTextChannel c)) throw new IllegalArgumentException("not a text channel");

		c.createUpdater()
		.setSlowmodeDelayInSeconds(delaySec)
		.setAuditLogReason(reason)
		.update().join();
	}

	static ChannelImpl wrap(org.javacord.api.entity.channel.Channel channel, DiscordImpl discord) {
		if (channel instanceof ServerChannel c) {
			return wrap(c, discord, null);
		} else if (channel instanceof PrivateChannel c) {
			return wrap(c, discord, null);
		} else {
			return null;
		}
	}

	static ChannelImpl wrap(ServerChannel channel, DiscordImpl discord, ServerImpl server) {
		if (channel == null) return null;

		if (server == null) server = ServerImpl.wrap(channel.getServer(), null);

		return new ChannelImpl(channel, discord, server, null);
	}

	static ChannelImpl wrap(PrivateChannel channel, DiscordImpl discord, UserImpl user) {
		if (channel == null) return null;

		if (user == null) user = UserImpl.wrap(channel.getRecipient().orElse(null), discord);

		return new ChannelImpl(channel, discord, null, user);
	}

	static ChannelImpl wrap(org.javacord.api.entity.channel.Channel channel, ChannelImpl refChannel) {
		if (channel.getId() == refChannel.getId()) {
			return refChannel;
		} else if (channel instanceof ServerChannel c) {
			ServerImpl server;

			if (refChannel.server != null && c.getServer().getId() == refChannel.server.getId()) {
				server = refChannel.server;
			} else {
				server = ServerImpl.wrap(c.getServer(), refChannel.getDiscord());
			}

			return wrap(c, refChannel.discord, server);
		} else if (channel instanceof PrivateChannel c) {
			return wrap(c, refChannel.discord, refChannel.user);
		} else {
			throw new IllegalArgumentException("need either server or user");
		}
	}

	org.javacord.api.entity.channel.Channel unwrap() {
		return wrapped;
	}
}
