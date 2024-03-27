/*
 * Copyright (c) 2021, 2022 FabricMC
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

package net.fabricmc.discord.bot.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import org.javacord.api.entity.Nameable;
import org.javacord.api.entity.channel.ChannelType;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.Messageable;
import org.javacord.api.entity.message.mention.AllowedMentions;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;
import org.jetbrains.annotations.Nullable;

public final class DiscordUtil {
	public static <T> T join(CompletableFuture<T> future) throws DiscordException {
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

	public static @Nullable TextChannel getTextChannel(Server server, long id) {
		ServerChannel ret = server.getChannelById(id).orElse(null);

		return ret instanceof TextChannel ? (TextChannel) ret : null;
	}

	public static List<TextChannel> getTextChannels(Server server) {
		List<ServerChannel> channels = server.getChannels();
		List<TextChannel> ret = new ArrayList<>(channels.size());

		for (ServerChannel channel : channels) {
			if (channel instanceof TextChannel) ret.add((TextChannel) channel);
		}

		return ret;
	}

	public static void sortTextChannelsByName(List<TextChannel> channels) {
		channels.sort((a, b) -> {
			if (a instanceof Nameable) {
				if (b instanceof Nameable) {
					return ((Nameable) a).getName().compareTo(((Nameable) b).getName());
				} else {
					return -1;
				}
			} else if (b instanceof Nameable) {
				return 1;
			} else {
				return 0;
			}
		});
	}

	public static final AllowedMentions NO_MENTIONS = new AllowedMentionsBuilder().build();

	public static CompletableFuture<Message> sendMentionlessMessage(Messageable target, CharSequence message) {
		return new MessageBuilder().append(message).setAllowedMentions(NO_MENTIONS).send(target);
	}

	public static boolean canDeleteMessages(TextChannel channel) {
		return channel.canYouSee() && channel.canYouReadMessageHistory() && channel.canYouManageMessages();
	}

	public static boolean canRemoveReactions(TextChannel channel) {
		ChannelType type = channel.getType();

		return channel.canYouRemoveReactionsOfOthers()
				&& type != ChannelType.PRIVATE_CHANNEL
				&& type != ChannelType.GROUP_CHANNEL; // no permissions in DMs
	}

	public static String getMessageLink(Server server, long channelId, long messageId) {
		return String.format("https://discord.com/channels/%s/%d/%d",
				(server != null ? Long.toUnsignedString(server.getId()) : "@me"),
				channelId,
				messageId);
	}

	public static final Pattern INVITE_PATTERN = Pattern.compile("(?:https://)?(?:www\\.)?discord(?:\\.gg|(?:\\.com|app\\.com)/invite)/([A-Za-z0-9]){2,10}");
}
