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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildUnavailableEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.guild.override.GenericPermissionOverrideEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.session.GenericSessionEvent;
import net.dv8tion.jda.api.events.session.SessionState;
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.GatewayIntent;

import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.DiscordBuilder.DiscordConfig;
import net.fabricmc.discord.io.DiscordProvider;
import net.fabricmc.discord.io.GlobalEventHolder;
import net.fabricmc.discord.io.GlobalEventHolder.ChannelCreateHandler;
import net.fabricmc.discord.io.GlobalEventHolder.ChannelDeleteHandler;
import net.fabricmc.discord.io.GlobalEventHolder.ChannelPermissionChangeHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MemberBanHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MemberJoinHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MemberLeaveHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MemberNicknameChangeHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageCreateHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageDeleteHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageEditHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageReactionAddHandler;
import net.fabricmc.discord.io.GlobalEventHolder.ServerGoneHandler;
import net.fabricmc.discord.io.GlobalEventHolder.ServerReadyHandler;
import net.fabricmc.discord.io.GlobalEventHolder.UserNameChangeHandler;

public class DiscordProviderImpl implements DiscordProvider {
	@Override
	public Discord create(DiscordConfig config) {
		Set<GatewayIntent> intents = EnumSet.allOf(GatewayIntent.class);
		intents.removeIf(intent -> !config.intents.get(intent.getOffset()));

		Wrapper wrapper = new Wrapper();
		GlobalEventHolder globalEventHolder = config.globalEventHolder;
		JDABuilder builder;

		if (config.cacheUsers) {
			builder = JDABuilder.create(config.accessToken, intents);
		} else {
			builder = JDABuilder.createLight(config.accessToken, intents);
		}

		// early event registrations to ensure nothing will be missed
		processEventRegistrations(globalEventHolder, wrapper, listener -> builder.addEventListeners(listener));

		JDA discord = builder.build();
		DiscordImpl ret = new DiscordImpl(discord, globalEventHolder);
		wrapper.init(ret);

		globalEventHolder.setUpdateHandler(() -> processEventRegistrations(globalEventHolder, wrapper, listener -> discord.addEventListener(listener)));

		return ret;
	}

	@FunctionalInterface
	private interface ListenerAdder {
		void addListener(EventListener listener);
	}

	private static void processEventRegistrations(GlobalEventHolder holder, Wrapper wrapper, ListenerAdder adder) {
		// server

		for (ServerReadyHandler handler : holder.removeHandlers(ServerReadyHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof GuildReadyEvent e) {
					handler.onReady(wrapper.wrap(e.getGuild()));
				} else if (event instanceof GuildAvailableEvent e) {
					handler.onReady(wrapper.wrap(e.getGuild()));
				}
			});
		}

		for (ServerGoneHandler handler : holder.removeHandlers(ServerGoneHandler.class)) {
			adder.addListener(new EventListener() {
				// JDA doesn't have a convenient inverse of GuildReadyEvent, track ready/available vs unavailable/disconnect/shutdown to emulate it
				private final Set<Guild> readyGuilds = new HashSet<>();

				@Override
				public void onEvent(GenericEvent event) {
					if (event instanceof GuildReadyEvent e) {
						readyGuilds.add(e.getGuild());
					} else if (event instanceof GuildAvailableEvent e) {
						readyGuilds.add(e.getGuild());
					} else if (event instanceof GuildUnavailableEvent e
							&& readyGuilds.remove(e.getGuild())) {
						handler.onGone(wrapper.wrap(e.getGuild()));
					} else if (event instanceof GenericSessionEvent e
							&& (e.getState() == SessionState.DISCONNECTED || e.getState() == SessionState.SHUTDOWN)) {
						for (Iterator<Guild> it = readyGuilds.iterator(); it.hasNext(); ) {
							handler.onGone(wrapper.wrap(it.next()));
							it.remove();
						}
					}
				}
			});
		}

		// channel

		for (ChannelCreateHandler handler : holder.removeHandlers(ChannelCreateHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof ChannelCreateEvent e) handler.onChannelCreate(wrapper.wrap(e.getChannel()));
			});
		}

		for (ChannelDeleteHandler handler : holder.removeHandlers(ChannelDeleteHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof ChannelDeleteEvent e) handler.onChannelDelete(wrapper.wrap(e.getChannel()));
			});
		}

		for (ChannelPermissionChangeHandler handler : holder.removeHandlers(ChannelPermissionChangeHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof GenericPermissionOverrideEvent e) handler.onChannelPermissionChange(wrapper.wrap(e.getChannel()));
			});
		}

		// member

		for (MemberJoinHandler handler : holder.removeHandlers(MemberJoinHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof GuildMemberJoinEvent e) handler.onMemberJoin(wrapper.wrap(e.getMember()));
			});
		}

		for (MemberLeaveHandler handler : holder.removeHandlers(MemberLeaveHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof GuildMemberRemoveEvent e) handler.onMemberLeave(wrapper.wrap(e.getMember()));
			});
		}

		for (MemberNicknameChangeHandler handler : holder.removeHandlers(MemberNicknameChangeHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof GuildMemberUpdateNicknameEvent e) handler.onMemberNicknameChange(wrapper.wrap(e.getMember()), e.getOldNickname(), e.getNewNickname());
			});
		}

		for (MemberBanHandler handler : holder.removeHandlers(MemberBanHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof GuildBanEvent e) handler.onMemberBan(wrapper.wrap(e.getUser()), wrapper.wrap(e.getGuild()));
			});
		}

		// message

		for (MessageCreateHandler handler : holder.removeHandlers(MessageCreateHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof MessageReceivedEvent e) handler.onMessageCreate(wrapper.wrap(e.getMessage()));
			});
		}

		for (MessageDeleteHandler handler : holder.removeHandlers(MessageDeleteHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof MessageDeleteEvent e) handler.onMessageDelete(e.getMessageIdLong(), wrapper.wrap(e.getChannel()));
			});
		}

		for (MessageEditHandler handler : holder.removeHandlers(MessageEditHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof MessageUpdateEvent e) handler.onMessageEdit(wrapper.wrap(e.getMessage()));
			});
		}

		for (MessageReactionAddHandler handler : holder.removeHandlers(MessageReactionAddHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof MessageReactionAddEvent e) {
					handler.onMessageReactionAdd(e.getMessageIdLong(), wrapper.wrap(e.getEmoji()), e.getUserIdLong(), wrapper.wrap(e.getChannel()));
				}
			});
		}

		// user

		for (UserNameChangeHandler handler : holder.removeHandlers(UserNameChangeHandler.class)) {
			adder.addListener(event -> {
				if (event instanceof UserUpdateNameEvent e) handler.onUserNameChange(wrapper.wrap(e.getUser()), e.getOldName(), e.getNewName());
			});
		}

		// leftovers

		Set<Class<?>> leftoverHandlers = holder.getHandlerTypes();

		if (!leftoverHandlers.isEmpty()) {
			throw new RuntimeException("leftover handlers: "+leftoverHandlers);
		}
	}

	class Wrapper {
		DiscordImpl discord;

		void init(DiscordImpl discord) {
			this.discord = discord;
		}

		ServerImpl wrap(Guild server) {
			return ServerImpl.wrap(server, discord);
		}

		ChannelImpl wrap(Channel channel) {
			return ChannelImpl.wrap(channel, discord);
		}

		MemberImpl wrap(Member member) {
			return MemberImpl.wrap(member, null, wrap(member.getGuild()));
		}

		MessageImpl wrap(Message message) {
			return MessageImpl.wrap(message, wrap(message.getChannel()));
		}

		EmojiImpl wrap(Emoji emoji) {
			return EmojiImpl.wrap(emoji, discord);
		}

		UserImpl wrap(User user) {
			return UserImpl.wrap(user, discord);
		}
	}
}
