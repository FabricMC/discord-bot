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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Server;

public final class DiscordUtil {
	public static Instant getCreationTime(long entityId) {
		return Instant.ofEpochMilli((entityId >>> 22) + 1420070400000L);
	}

	public static List<? extends Channel> getTextChannels(Server server) {
		List<? extends Channel> channels = server.getChannels();
		List<Channel> ret = new ArrayList<>(channels.size());

		for (Channel channel : channels) {
			if (channel.getType().text) ret.add(channel);
		}

		return ret;
	}

	public static void sortTextChannelsByName(List<Channel> channels) {
		channels.sort(Comparator.nullsLast(Comparator.comparing(Channel::getName)));
	}

	public static Message sendMentionlessMessage(Channel target, CharSequence message) {
		return target.send(new Message.Builder().content(message.toString()).noAllowedMentions().build());
	}

	public static boolean canDeleteMessages(Channel channel) {
		return channel.canYouSee() && channel.haveYouPermission(Permission.READ_MESSAGE_HISTORY) && channel.haveYouPermission(Permission.MANAGE_MESSAGES);
	}

	public static boolean canRemoveReactions(Channel channel) {
		return channel.haveYouPermission(Permission.MANAGE_MESSAGES);
	}

	public static String getMessageLink(Server server, long channelId, long messageId) {
		return String.format("https://discord.com/channels/%s/%d/%d",
				(server != null ? Long.toUnsignedString(server.getId()) : "@me"),
				channelId,
				messageId);
	}

	public static final Pattern INVITE_PATTERN = Pattern.compile("(?:https://)?(?:www\\.)?discord(?:\\.gg|(?:\\.com|app\\.com)/invite)/([A-Za-z0-9]){2,10}");
}
