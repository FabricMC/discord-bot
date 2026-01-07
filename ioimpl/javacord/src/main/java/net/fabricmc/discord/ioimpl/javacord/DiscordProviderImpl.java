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

import java.net.URL;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.listener.GloballyAttachableListener;
import org.javacord.api.listener.channel.server.ServerChannelChangeOverwrittenPermissionsListener;
import org.javacord.api.listener.channel.server.ServerChannelCreateListener;
import org.javacord.api.listener.channel.server.ServerChannelDeleteListener;
import org.javacord.api.listener.channel.server.thread.ServerThreadChannelCreateListener;
import org.javacord.api.listener.channel.server.thread.ServerThreadChannelDeleteListener;
import org.javacord.api.listener.channel.user.PrivateChannelCreateListener;
import org.javacord.api.listener.channel.user.PrivateChannelDeleteListener;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.listener.message.MessageDeleteListener;
import org.javacord.api.listener.message.MessageEditListener;
import org.javacord.api.listener.message.reaction.ReactionAddListener;
import org.javacord.api.listener.server.ServerBecomesAvailableListener;
import org.javacord.api.listener.server.ServerBecomesUnavailableListener;
import org.javacord.api.listener.server.member.ServerMemberBanListener;
import org.javacord.api.listener.server.member.ServerMemberJoinListener;
import org.javacord.api.listener.server.member.ServerMemberLeaveListener;
import org.javacord.api.listener.user.UserChangeNameListener;
import org.javacord.api.listener.user.UserChangeNicknameListener;

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
		Wrapper wrapper = new Wrapper();
		GlobalEventHolder globalEventHolder = config.globalEventHolder;
		DiscordApiBuilder builder = new DiscordApiBuilder();

		// early event registrations to ensure nothing will be missed
		processEventRegistrations(globalEventHolder, wrapper, new ListenerAdder() {
			@Override
			public <T extends GloballyAttachableListener> void addListener(Class<T> listenerClass, T listener) {
				builder.addListener(listenerClass, listener);
			}
		});

		DiscordApi discord = builder
				.setWaitForUsersOnStartup(config.cacheUsers)
				.setAllIntentsWhere(i -> config.intents.get(i.getId()))
				.setToken(config.accessToken)
				.login()
				.join();

		DiscordImpl ret = new DiscordImpl(discord, globalEventHolder);
		wrapper.init(ret);

		globalEventHolder.setUpdateHandler(() -> processEventRegistrations(globalEventHolder, wrapper, new ListenerAdder() {
			@Override
			public <T extends GloballyAttachableListener> void addListener(Class<T> listenerClass, T listener) {
				discord.addListener(listenerClass, listener);
			}
		}));

		return ret;
	}

	@FunctionalInterface
	private interface ListenerAdder {
		<T extends GloballyAttachableListener> void addListener(Class<T> listenerClass, T listener);
	}

	private static void processEventRegistrations(GlobalEventHolder holder, Wrapper wrapper, ListenerAdder adder) {
		// server

		for (ServerReadyHandler handler : holder.removeHandlers(ServerReadyHandler.class)) {
			adder.addListener(ServerBecomesAvailableListener.class, event -> {
				handler.onReady(wrapper.wrap(event.getServer()));
			});
		}

		for (ServerGoneHandler handler : holder.removeHandlers(ServerGoneHandler.class)) {
			adder.addListener(ServerBecomesUnavailableListener.class, event -> {
				handler.onGone(wrapper.wrap(event.getServer()));
			});
		}

		// channel

		for (ChannelCreateHandler handler : holder.removeHandlers(ChannelCreateHandler.class)) {
			adder.addListener(ServerChannelCreateListener.class, event -> {
				handler.onChannelCreate(wrapper.wrap(event.getChannel()));
			});

			adder.addListener(ServerThreadChannelCreateListener.class, event -> {
				handler.onChannelCreate(wrapper.wrap(event.getChannel()));
			});

			adder.addListener(PrivateChannelCreateListener.class, event -> {
				handler.onChannelCreate(wrapper.wrap(event.getChannel()));
			});
		}

		for (ChannelDeleteHandler handler : holder.removeHandlers(ChannelDeleteHandler.class)) {
			adder.addListener(ServerChannelDeleteListener.class, event -> {
				handler.onChannelDelete(wrapper.wrap(event.getChannel()));
			});

			adder.addListener(ServerThreadChannelDeleteListener.class, event -> {
				handler.onChannelDelete(wrapper.wrap(event.getChannel()));
			});

			adder.addListener(PrivateChannelDeleteListener.class, event -> {
				handler.onChannelDelete(wrapper.wrap(event.getChannel()));
			});
		}

		for (ChannelPermissionChangeHandler handler : holder.removeHandlers(ChannelPermissionChangeHandler.class)) {
			adder.addListener(ServerChannelChangeOverwrittenPermissionsListener.class, event -> {
				handler.onChannelPermissionChange(wrapper.wrap(event.getChannel()));
			});
		}

		// member

		for (MemberJoinHandler handler : holder.removeHandlers(MemberJoinHandler.class)) {
			adder.addListener(ServerMemberJoinListener.class, event -> {
				handler.onMemberJoin(wrapper.wrap(event.getUser(), event.getServer()));
			});
		}

		for (MemberLeaveHandler handler : holder.removeHandlers(MemberLeaveHandler.class)) {
			adder.addListener(ServerMemberLeaveListener.class, event -> {
				handler.onMemberLeave(wrapper.wrap(event.getUser(), event.getServer()));
			});
		}

		for (MemberNicknameChangeHandler handler : holder.removeHandlers(MemberNicknameChangeHandler.class)) {
			adder.addListener(UserChangeNicknameListener.class, event -> {
				handler.onMemberNicknameChange(wrapper.wrap(event.getUser(), event.getServer()), event.getOldNickname().orElse(null), event.getNewNickname().orElse(null));
			});
		}

		for (MemberBanHandler handler : holder.removeHandlers(MemberBanHandler.class)) {
			adder.addListener(ServerMemberBanListener.class, event -> {
				handler.onMemberBan(wrapper.wrap(event.getUser()), wrapper.wrap(event.getServer()));
			});
		}

		// message

		for (MessageCreateHandler handler : holder.removeHandlers(MessageCreateHandler.class)) {
			adder.addListener(MessageCreateListener.class, event -> {
				handler.onMessageCreate(wrapper.wrap(event.getMessage()));
			});
		}

		for (MessageDeleteHandler handler : holder.removeHandlers(MessageDeleteHandler.class)) {
			adder.addListener(MessageDeleteListener.class, event -> {
				handler.onMessageDelete(event.getMessageId(), wrapper.wrap(event.getChannel()));
			});
		}

		for (MessageEditHandler handler : holder.removeHandlers(MessageEditHandler.class)) {
			adder.addListener(MessageEditListener.class, event -> {
				handler.onMessageEdit(wrapper.wrap(event.getMessage()));
			});
		}

		for (MessageReactionAddHandler handler : holder.removeHandlers(MessageReactionAddHandler.class)) {
			adder.addListener(ReactionAddListener.class, event -> {
				handler.onMessageReactionAdd(event.getMessageId(), wrapper.wrap(event.getEmoji()), event.getUserId(), wrapper.wrap(event.getChannel()));
			});
		}

		// user

		for (UserNameChangeHandler handler : holder.removeHandlers(UserNameChangeHandler.class)) {
			adder.addListener(UserChangeNameListener.class, event -> {
				handler.onUserNameChange(wrapper.wrap(event.getUser()), event.getOldName(), event.getNewName());
			});
		}

		// leftovers

		Set<Class<?>> leftoverHandlers = holder.getHandlerTypes();

		if (!leftoverHandlers.isEmpty()) {
			throw new RuntimeException("leftover handlers: "+leftoverHandlers);
		}

		/*for (Object handler : config.globalEventHolder.getHandlers()) {
			switch (handler) {
			case ServerReadyHandler h -> builder.addServerBecomesAvailableListener(event -> {
				h.onReady(wrapper.wrap(event.getServer()));
			});
			default -> throw new RuntimeException("unhandled event handler type: "+handler);
			}
		}*/
	}

	class Wrapper {
		DiscordImpl discord;

		void init(DiscordImpl discord) {
			this.discord = discord;
		}

		ServerImpl wrap(Server server) {
			return ServerImpl.wrap(server, discord);
		}

		ChannelImpl wrap(Channel channel) {
			return ChannelImpl.wrap(channel, discord);
		}

		MemberImpl wrap(User user, Server server) { // member
			return MemberImpl.wrap(user, wrap(server));
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

	static <T> T join(CompletableFuture<T> future) throws DiscordException {
		try {
			return future.join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();

			if (cause instanceof DiscordException) {
				throw (DiscordException) cause;
			} else if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			} else {
				throw e;
			}
		}
	}

	static String urlToString(URL url) {
		return url != null ? url.toString() : null;
	}

	static String urlToString(Optional<URL> url) {
		return url.isPresent() ? url.get().toString() : null;
	}
}
