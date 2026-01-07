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


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.attribute.ISlowmodeChannel;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.User;
import net.fabricmc.discord.io.Wrapper;

public class ChannelImpl implements Channel {
	private static final Wrapper<net.dv8tion.jda.api.entities.channel.Channel, ChannelImpl> WRAPPER = new Wrapper<>();

	private final net.dv8tion.jda.api.entities.channel.Channel wrapped;
	private final DiscordImpl discord;
	private final ServerImpl server;
	private final UserImpl user;

	ChannelImpl(net.dv8tion.jda.api.entities.channel.Channel wrapped, DiscordImpl discord, ServerImpl server, UserImpl user) {
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
		return wrapped.getIdLong();
	}

	@Override
	public Type getType() {
		return Type.fromId(wrapped.getType().getId());
	}

	@Override
	public String getName() {
		return wrapped.getName();
	}

	@Override
	public Set<Permission> getPermissions(User user) {
		if (wrapped instanceof GuildChannel) {
			return server.getMember(user.getId()).getPermissions(this);
		} else if (wrapped instanceof PrivateChannel) {
			return Permission.DM;
		} else {
			throw new IllegalStateException("not a suitable channel type");
		}
	}

	@Override
	public PermissionOverwriteData getPermissionOverwrites(Role role) {
		if (wrapped instanceof GuildChannel c) {
			PermissionOverride res = c.getPermissionContainer().getPermissionOverride(((RoleImpl) role).unwrap());

			return new PermissionOverwriteData(Permission.fromMask(res.getAllowedRaw()), Permission.fromMask(res.getDeniedRaw()));
		} else {
			throw new IllegalStateException("not a suitable channel type");
		}
	}

	@Override
	public void setPermissionOverwrites(Role role, PermissionOverwriteData data, String reason) {
		if (wrapped instanceof GuildChannel c) {
			c.getPermissionContainer().getManager()
			.putPermissionOverride(((RoleImpl) role).unwrap(), Permission.toMask(data.allowed()), Permission.toMask(data.denied()))
			.reason(reason)
			.complete();
		} else {
			throw new IllegalStateException("not a suitable channel type");
		}
	}

	@Override
	public Message getMessage(long id) {
		if (wrapped instanceof MessageChannel channel) {
			return MessageImpl.wrap(channel.retrieveMessageById(id).complete(), this);
		} else {
			return null;
		}
	}

	@Override
	public List<MessageImpl> getMessages(int limit) {
		if (wrapped instanceof MessageChannel channel) {
			List<net.dv8tion.jda.api.entities.Message> res = channel.getIterableHistory().cache(false).takeAsync(limit).join();

			return DiscordImplUtil.wrap(res, r -> MessageImpl.wrap(r, this));
		} else {
			return Collections.emptyList();
		}
	}

	@Override
	public List<? extends Message> getMessagesBetween(long firstId, long lastId, int limit) {
		if (wrapped instanceof MessageChannel channel) {
			if (limit < 0) limit = Integer.MAX_VALUE;
			List<Message> ret = new ArrayList<>();

			retrieveLoop: while (limit > 0) {
				List<net.dv8tion.jda.api.entities.Message> res = channel.getHistoryAfter(firstId, Math.min(100, limit)).complete().getRetrievedHistory();

				for (net.dv8tion.jda.api.entities.Message msg : res) {
					if (msg.getIdLong() >= lastId) break retrieveLoop;

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
		if (!(wrapped instanceof MessageChannel c)) throw new IllegalArgumentException("not a message channel");

		return MessageImpl.wrap(c.sendMessage(message).complete(), this);
	}

	@Override
	public Message send(MessageEmbed message) {
		if (!(wrapped instanceof MessageChannel c)) throw new IllegalArgumentException("not a message channel");

		net.dv8tion.jda.api.entities.MessageEmbed embed = MessageEmbedImpl.toBuilder(message).build();

		return MessageImpl.wrap(c.sendMessageEmbeds(embed).complete(), this);
	}

	@Override
	public Message send(Message message) {
		if (!(wrapped instanceof MessageChannel c)) throw new IllegalArgumentException("not a message channel");

		MessageCreateData msg = MessageImpl.toCreateData(message);

		return MessageImpl.wrap(c.sendMessage(msg).complete(), this);
	}

	@Override
	public void deleteMessage(long id, String reason) {
		if (!(wrapped instanceof MessageChannel c)) throw new IllegalArgumentException("not a message channel");

		c.deleteMessageById(id).reason(reason).complete();
	}

	@Override
	public void deleteMessages(long[] messageIds, String reason) {
		if (!(wrapped instanceof MessageChannel c)) throw new IllegalArgumentException("not a message channel");

		c.purgeMessagesById(messageIds);
	}

	@Override
	public int getSlowmodeDelaySeconds() {
		if (!(wrapped instanceof ISlowmodeChannel c)) throw new IllegalArgumentException("not a slowmode capable channel");

		return c.getSlowmode();
	}

	@Override
	public void setSlowmodeDelaySeconds(int delaySec, String reason) {
		if (!(wrapped instanceof ISlowmodeChannel c)) throw new IllegalArgumentException("not a slowmode capable channel");

		c.getManager().setSlowmode(delaySec).reason(reason).complete();
	}

	static ChannelImpl wrap(net.dv8tion.jda.api.entities.channel.Channel channel, DiscordImpl discord) {
		if (channel instanceof GuildChannel c) {
			return wrap(c, discord, null);
		} else if (channel instanceof PrivateChannel c) {
			return wrap(c, discord, null);
		} else {
			return null;
		}
	}

	static ChannelImpl wrap(GuildChannel channel, DiscordImpl discord, ServerImpl server) {
		if (channel == null) return null;

		if (server == null) server = ServerImpl.wrap(channel.getGuild(), null);

		return wrap(channel, discord, server, null);
	}

	static ChannelImpl wrap(PrivateChannel channel, DiscordImpl discord, UserImpl user) {
		if (channel == null) return null;

		if (user == null) user = UserImpl.wrap(channel.retrieveUser().complete(), discord);

		return wrap(channel, discord, null, user);
	}

	private static ChannelImpl wrap(net.dv8tion.jda.api.entities.channel.Channel channel, DiscordImpl discord, ServerImpl server, UserImpl user) {
		return WRAPPER.wrap(channel, c -> new ChannelImpl(c, discord, server, user));
	}

	static ChannelImpl wrap(net.dv8tion.jda.api.entities.channel.Channel channel, ChannelImpl refChannel) {
		if (channel.getIdLong() == refChannel.getId()) {
			return refChannel;
		} else if (channel instanceof GuildChannel c) {
			ServerImpl server;

			if (refChannel.server != null && c.getGuild().getIdLong() == refChannel.server.getId()) {
				server = refChannel.server;
			} else {
				server = ServerImpl.wrap(c.getGuild(), refChannel.getDiscord());
			}

			return wrap(c, refChannel.discord, server);
		} else if (channel instanceof PrivateChannel c) {
			return wrap(c, refChannel.discord, refChannel.user);
		} else {
			throw new IllegalArgumentException("need either server or user");
		}
	}

	net.dv8tion.jda.api.entities.channel.Channel unwrap() {
		return wrapped;
	}
}
