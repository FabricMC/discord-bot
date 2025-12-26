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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Message;

public final class DeleteRangeCommand extends Command {
	private static final int MESSAGE_COUNT_LIMIT = 100;
	private static final int USER_LIST_LIMIT = 8;

	private final Map<Integer, ActionEntry> pendingActions = new ConcurrentHashMap<>();

	@Override
	public String name() {
		return "deleterange";
	}

	@Override
	public String usage() {
		return "(confirm <id> | <firstMessage> <lastMessage> <reason...>)";
	}

	@Override
	public String permission() {
		return "deleterange";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		if (!"confirm".equals(arguments.get("unnamed_0"))) {
			CachedMessage firstCachedMessage = getMessage(context, arguments.get("firstMessage"), false);
			if (firstCachedMessage == null) throw new CommandException("can't find firstMessage");
			CachedMessage lastCachedMessage = getMessage(context, arguments.get("lastMessage"), false);
			if (lastCachedMessage == null) throw new CommandException("can't find lastMessage");

			Message firstMessage = firstCachedMessage.toMessage(context.server());
			if (firstMessage == null) throw new CommandException("Can't resolve firstMessage");

			Channel channel = firstMessage.getChannel();
			checkMessageDeleteAccess(context, channel);

			List<Message> messages = new ArrayList<>();
			messages.add(firstMessage);
			messages.addAll(channel.getMessagesBetween(firstMessage.getId(), lastCachedMessage.getId(), MESSAGE_COUNT_LIMIT));
			messages.add(lastCachedMessage.toMessage(context.server()));

			Function<Long, Boolean> immunityCheck = targetDUId ->  context.bot().getUserHandler().hasImmunity(targetDUId, context.userId(), true);
			Map<Long, Boolean> immunityData = new HashMap<>();
			messages.removeIf(msg -> immunityData.computeIfAbsent(msg.getAuthor().getId(), immunityCheck));

			if (messages.isEmpty()) {
				throw new CommandException("No messages");
			} else {
				Set<String> users = new LinkedHashSet<>(USER_LIST_LIMIT + 1);

				for (Message msg : messages) {
					String userName = msg.getAuthor().getName();

					if (users.size() >= USER_LIST_LIMIT && users.contains(userName)) {
						users.add("…");
						break;
					} else {
						users.add(userName);
					}
				}

				int id = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
				pendingActions.put(id, new ActionEntry(messages, arguments.get("reason")));
				context.bot().getScheduledExecutor().schedule(() -> pendingActions.remove(id), 5, TimeUnit.MINUTES);

				context.channel().send(String.format("You are about to delete %d messages by %s from <#%d>, use `%s%s confirm %d` to continue",
						messages.size(),
						String.join(", ", users),
						channel.getId(),
						context.bot().getCommandPrefix(),
						name(),
						id));
			}
		} else {
			ActionEntry entry = pendingActions.remove(Integer.parseInt(arguments.get("id")));
			if (entry == null) throw new CommandException("Invalid id");

			for (Message m : entry.messages) { // TODO: bulk delete / parallel execution
				m.delete(entry.reason);
			}

			context.channel().send("Messages deleted");
		}

		return true;
	}

	private record ActionEntry(List<Message> messages, String reason) { }
}
