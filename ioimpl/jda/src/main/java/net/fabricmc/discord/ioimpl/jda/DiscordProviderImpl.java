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

import java.net.SocketTimeoutException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
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
import net.dv8tion.jda.api.requests.RestAction;

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

		EventAdapter eventAdapter = new EventAdapter();
		builder.addEventListeners(eventAdapter);

		// early event registrations to ensure nothing will be missed
		processEventRegistrations(globalEventHolder, wrapper, eventAdapter);

		JDA discord = builder.build();
		DiscordImpl ret = new DiscordImpl(discord, globalEventHolder);
		wrapper.init(ret);

		globalEventHolder.setUpdateHandler(() -> processEventRegistrations(globalEventHolder, wrapper, eventAdapter));

		return ret;
	}

	@FunctionalInterface
	private interface ListenerAdder {
		void addListener(EventListener listener);
	}

	private static void processEventRegistrations(GlobalEventHolder holder, Wrapper wrapper, EventAdapter adapter) {
		// server

		for (ServerReadyHandler handler : holder.removeHandlers(ServerReadyHandler.class)) {
			// JDA doesn't have a convenient inverse of GuildReadyEvent, track ready/available vs unavailable/disconnect/shutdown to emulate it
			final Set<Guild> seenGuilds = new HashSet<>();

			adapter.register(GuildReadyEvent.class, e -> {
				System.out.printf("GuildReadyEvent %s -> onReady%n", e.getGuild().getName());
				seenGuilds.add(e.getGuild());
				handler.onReady(wrapper.wrap(e.getGuild()));
			});

			adapter.register(GuildAvailableEvent.class, e -> {
				System.out.printf("GuildAvailableEvent %s -> onReady%n", e.getGuild().getName());
				//seenGuilds.add(e.getGuild());
				handler.onReady(wrapper.wrap(e.getGuild()));
			});

			adapter.register(GenericSessionEvent.class, e -> {
				if (e.getState() == SessionState.RESUMED) {
					System.out.printf("GenericSessionEvent %s -> onReady (%d)%n", e.getState().name(), seenGuilds.size());

					for (Guild guild : seenGuilds) {
						handler.onReady(wrapper.wrap(guild));
					}
				}
			});
		}

		for (ServerGoneHandler handler : holder.removeHandlers(ServerGoneHandler.class)) {
			// JDA doesn't have a convenient inverse of GuildReadyEvent, track ready/available vs unavailable/disconnect/shutdown to emulate it
			final Set<Guild> readyGuilds = new HashSet<>();

			adapter.register(GuildReadyEvent.class, e -> {
				readyGuilds.add(e.getGuild());
			});

			adapter.register(GuildAvailableEvent.class, e -> {
				readyGuilds.add(e.getGuild());
			});

			adapter.register(GuildUnavailableEvent.class, e -> {
				if (readyGuilds.remove(e.getGuild())) {
					System.out.printf("GuildUnavailableEvent %s -> onGone%n", e.getGuild().getName());
					handler.onGone(wrapper.wrap(e.getGuild()));
				} else {
					System.out.printf("GuildUnavailableEvent %s%n", e.getGuild().getName());
				}
			});

			adapter.register(GenericSessionEvent.class, e -> {
				if (e.getState() == SessionState.DISCONNECTED || e.getState() == SessionState.SHUTDOWN) {
					System.out.printf("GenericSessionEvent %s -> onGone (%d)%n", e.getState().name(), readyGuilds.size());

					for (Iterator<Guild> it = readyGuilds.iterator(); it.hasNext(); ) {
						handler.onGone(wrapper.wrap(it.next()));
						if (e.getState() != SessionState.DISCONNECTED) it.remove();
					}
				} else {
					System.out.printf("GenericSessionEvent %s%n", e.getState().name());
				}
			});
		}

		// channel

		for (ChannelCreateHandler handler : holder.removeHandlers(ChannelCreateHandler.class)) {
			adapter.register(ChannelCreateEvent.class, e -> handler.onChannelCreate(wrapper.wrap(e.getChannel())));
		}

		for (ChannelDeleteHandler handler : holder.removeHandlers(ChannelDeleteHandler.class)) {
			adapter.register(ChannelDeleteEvent.class, e -> handler.onChannelDelete(wrapper.wrap(e.getChannel())));
		}

		for (ChannelPermissionChangeHandler handler : holder.removeHandlers(ChannelPermissionChangeHandler.class)) {
			adapter.register(GenericPermissionOverrideEvent.class, e -> handler.onChannelPermissionChange(wrapper.wrap(e.getChannel())));
		}

		// member

		for (MemberJoinHandler handler : holder.removeHandlers(MemberJoinHandler.class)) {
			adapter.register(GuildMemberJoinEvent.class, e -> handler.onMemberJoin(wrapper.wrap(e.getMember())));
		}

		for (MemberLeaveHandler handler : holder.removeHandlers(MemberLeaveHandler.class)) {
			adapter.register(GuildMemberRemoveEvent.class, e -> handler.onMemberLeave(wrapper.wrap(e.getMember())));
		}

		for (MemberNicknameChangeHandler handler : holder.removeHandlers(MemberNicknameChangeHandler.class)) {
			adapter.register(GuildMemberUpdateNicknameEvent.class, e -> handler.onMemberNicknameChange(wrapper.wrap(e.getMember()), e.getOldNickname(), e.getNewNickname()));
		}

		for (MemberBanHandler handler : holder.removeHandlers(MemberBanHandler.class)) {
			adapter.register(GuildBanEvent.class, e -> handler.onMemberBan(wrapper.wrap(e.getUser()), wrapper.wrap(e.getGuild())));
		}

		// message

		for (MessageCreateHandler handler : holder.removeHandlers(MessageCreateHandler.class)) {
			adapter.register(MessageReceivedEvent.class, e -> handler.onMessageCreate(wrapper.wrap(e.getMessage())));
		}

		for (MessageDeleteHandler handler : holder.removeHandlers(MessageDeleteHandler.class)) {
			adapter.register(MessageDeleteEvent.class, e -> handler.onMessageDelete(e.getMessageIdLong(), wrapper.wrap(e.getChannel())));
		}

		for (MessageEditHandler handler : holder.removeHandlers(MessageEditHandler.class)) {
			adapter.register(MessageUpdateEvent.class, e -> handler.onMessageEdit(wrapper.wrap(e.getMessage())));
		}

		for (MessageReactionAddHandler handler : holder.removeHandlers(MessageReactionAddHandler.class)) {
			adapter.register(MessageReactionAddEvent.class, e -> handler.onMessageReactionAdd(e.getMessageIdLong(), wrapper.wrap(e.getEmoji()), e.getUserIdLong(), wrapper.wrap(e.getChannel())));
		}

		// user

		for (UserNameChangeHandler handler : holder.removeHandlers(UserNameChangeHandler.class)) {
			adapter.register(UserUpdateNameEvent.class, e -> handler.onUserNameChange(wrapper.wrap(e.getUser()), e.getOldName(), e.getNewName()));
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
			return MessageImpl.wrap(message, discord, wrap(message.getChannel()));
		}

		EmojiImpl wrap(Emoji emoji) {
			return EmojiImpl.wrap(emoji, discord);
		}

		UserImpl wrap(User user) {
			return UserImpl.wrap(user, discord);
		}
	}

	@Override
	public String toString() {
		return "JDA";
	}

	static <T> T fetchWithRetry(Supplier<RestAction<T>> actionSupplier, int timeoutSec, int maxAttempts) {
		int attempt = 0;

		attemptLoop: for (;;) {
			try {
				RestAction<T> action = actionSupplier.get();
				if (timeoutSec > 0) action = action.timeout(timeoutSec, TimeUnit.SECONDS);

				Future<T> res = action.submit();

				return timeoutSec > 0 ? res.get(timeoutSec + 5, TimeUnit.SECONDS) : res.get(); // use future timeout if the rest action timeout is unreliable
			} catch (ExecutionException | TimeoutException e) {
				if (++attempt < maxAttempts && isTimeout(e)) {
					continue attemptLoop;
				}

				Throwable exc = e;
				if (exc instanceof ExecutionException) exc = exc.getCause();

				if (exc instanceof RuntimeException rte) {
					throw rte;
				} else {
					throw new RuntimeException(e);
				}
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static boolean isTimeout(Throwable exc) {
		for (int i = 0; exc != null && i < 5; i++) { // search max 5 deep
			if (exc instanceof TimeoutException || exc instanceof SocketTimeoutException) return true;

			exc = exc.getCause();
		}

		return false;
	}
}
