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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.javacord.api.entity.channel.ServerTextChannel;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;

public final class CleanCommand extends Command {
	private final Map<Integer, List<ChannelEntry>> pendingActions = new ConcurrentHashMap<>();

	@Override
	public String name() {
		return "clean";
	}

	@Override
	public String usage() {
		return "(confirm <id> | <user> [<channel>])";
	}

	@Override
	public String permission() {
		return "clean";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		if (!"confirm".equals(arguments.get("unnamed_0"))) {
			long targetDiscordUserId = getDiscordUserId(context, arguments.get("user"));
			checkImmunity(context, targetDiscordUserId, true);

			String targetChannelName = arguments.get("channel");
			Collection<ServerTextChannel> targetChannels = targetChannelName != null ? Collections.singletonList(getTextChannel(context, targetChannelName)) : context.server().getTextChannels();

			List<ChannelEntry> actions = new ArrayList<>();
			int count = 0;

			for (ServerTextChannel channel : targetChannels) {
				if (!channel.canYouSee() || !channel.canYouReadMessageHistory() || !channel.canYouManageMessages()) continue;
				if (!channel.canSee(context.user())) continue;

				long[] messageIds = context.bot().getMessageIndex().getAllIdsByAuthor(channel, targetDiscordUserId, false);

				if (messageIds.length > 0) {
					actions.add(new ChannelEntry(channel, messageIds));
					count += messageIds.length;
				}
			}

			if (count == 0) {
				throw new CommandException("No messages");
			} else {
				int id = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
				pendingActions.put(id, actions);
				context.bot().getScheduledExecutor().schedule(() -> pendingActions.remove(id), 5, TimeUnit.MINUTES);

				context.channel().sendMessage(String.format("You are about to delete %d messages by %s, use `%s%s confirm %d` to continue",
						count,
						context.bot().getUserHandler().formatDiscordUser(targetDiscordUserId, context.server()),
						context.bot().getCommandPrefix(),
						name(),
						id)); // TODO: suppress mentions once https://github.com/Javacord/Javacord/pull/587 is released
			}
		} else {
			List<ChannelEntry> actions = pendingActions.remove(Integer.parseInt(arguments.get("id")));
			if (actions == null) throw new CommandException("Invalid id");

			for (ChannelEntry entry : actions) {
				entry.channel.deleteMessages(entry.messageIds);
			}

			context.channel().sendMessage("Messages deleted");
		}

		return true;
	}

	private record ChannelEntry(ServerTextChannel channel, long[] messageIds) { }
}
