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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.util.DiscordUtil;

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
			ServerTextChannel targetChannel = targetChannelName != null ? getTextChannel(context, targetChannelName) : null;

			List<ChannelEntry> actions = new ArrayList<>();
			int count = gatherActions(LongSet.of(targetDiscordUserId), targetChannel, context, actions);

			if (count == 0) {
				throw new CommandException("No messages");
			} else {
				int id = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
				pendingActions.put(id, actions);
				context.bot().getScheduledExecutor().schedule(() -> pendingActions.remove(id), 5, TimeUnit.MINUTES);

				DiscordUtil.sendMentionlessMessage(context.channel(), String.format("You are about to delete %d messages by %s, use `%s%s confirm %d` to continue",
						count,
						context.bot().getUserHandler().formatDiscordUser(targetDiscordUserId, context.server()),
						context.bot().getCommandPrefix(),
						name(),
						id));
			}
		} else {
			List<ChannelEntry> actions = pendingActions.remove(Integer.parseInt(arguments.get("id")));
			if (actions == null) throw new CommandException("Invalid id");

			applyActions(actions, null);

			context.channel().sendMessage("Messages deleted");
		}

		return true;
	}

	private static int gatherActions(LongSet targetDiscordUserIds, ServerTextChannel targetChannel, CommandContext context, List<ChannelEntry> actions) {
		Collection<ServerTextChannel> targetChannels = targetChannel != null ? Collections.singletonList(targetChannel) : context.server().getTextChannels();
		int count = 0;

		for (ServerTextChannel channel : targetChannels) {
			if (!channel.canYouSee() || !channel.canYouReadMessageHistory() || !channel.canYouManageMessages()) continue;
			if (!channel.canSee(context.user())) continue;

			Collection<CachedMessage> messages = context.bot().getMessageIndex().getAllIdsByAuthors(channel, targetDiscordUserIds, false);

			if (!messages.isEmpty()) {
				actions.add(new ChannelEntry(channel, messages));
				count += messages.size();
			}
		}

		return count;
	}

	private static void applyActions(List<ChannelEntry> actions, String reason) throws DiscordException {
		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (ChannelEntry entry : actions) {
			if (entry.messages().size() == 1) {
				CachedMessage msg = entry.messages().iterator().next();

				if (!msg.isDeleted()) { // reduce delete race potential
					futures.add(Message.delete(entry.channel().getApi(), entry.channel().getId(), msg.getId(), reason));
				}
			} else {
				long[] msgIds = new long[entry.messages().size()];
				int writeIdx = 0;

				for (CachedMessage msg : entry.messages()) {
					if (!msg.isDeleted()) { // reduce delete race potential
						msgIds[writeIdx++] = msg.getId();
					}
				}

				if (writeIdx != msgIds.length) msgIds = Arrays.copyOf(msgIds, writeIdx);

				futures.add(entry.channel().deleteMessages(msgIds));
			}
		}

		for (CompletableFuture<?> future : futures) {
			try {
				DiscordUtil.join(future);
			} catch (NotFoundException e) {
				// ignore, presumably already deleted
			}
		}
	}

	static Collection<CachedMessage> clean(int targetUserId, ServerTextChannel targetChannel, String reason, CommandContext context) throws DiscordException {
		LongSet targetDiscordUserIds = new LongOpenHashSet(context.bot().getUserHandler().getDiscordUserIds(targetUserId));

		List<ChannelEntry> actions = new ArrayList<>();
		int count = gatherActions(targetDiscordUserIds, targetChannel, context, actions);
		applyActions(actions, reason);

		List<CachedMessage> ret = new ArrayList<>(count);

		for (ChannelEntry entry : actions) {
			ret.addAll(entry.messages());
		}

		return ret;
	}

	private record ChannelEntry(ServerTextChannel channel, Collection<CachedMessage> messages) { }
}
