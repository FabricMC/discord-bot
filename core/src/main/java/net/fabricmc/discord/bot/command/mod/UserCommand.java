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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.javacord.api.entity.DiscordEntity;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.entity.user.UserStatus;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActiveActionEntry;
import net.fabricmc.discord.bot.database.query.UserQueries.DiscordUserData;
import net.fabricmc.discord.bot.database.query.UserQueries.UserData;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.message.Paginator.Page;
import net.fabricmc.discord.bot.util.FormatUtil;

public final class UserCommand extends Command {
	@Override
	public String name() {
		return "user";
	}

	@Override
	public List<String> aliases() {
		return List.of("u");
	}

	@Override
	public String usage() {
		return "<user>";
	}

	@Override
	public String permission() {
		return "user";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		UserHandler userHandler = context.bot().getUserHandler();
		int targetUserId = getUserId(context, arguments.get("user"));
		UserData userData = userHandler.getUserData(targetUserId, false, false);
		if (userData == null) throw new CommandException("no data for user "+targetUserId);

		String title = "User %d Info".formatted(targetUserId);
		long currentTime = System.currentTimeMillis();
		Server server = context.server();

		DiscordUserData firstDiscordUser = null;
		StringBuilder discordUserList = new StringBuilder();
		Instant createdTime, firstSeenTime, lastSeenTime;

		if (!userData.discordUsers().isEmpty()) {
			// determine create+join+seen times across all associated discord accs, build user list

			long created = Long.MAX_VALUE;
			long firstSeen = Long.MAX_VALUE;
			long lastSeen = Long.MIN_VALUE;
			int num = 0;

			for (DiscordUserData du : userData.discordUsers()) {
				if (firstDiscordUser == null) firstDiscordUser = du;

				created = Math.min(created, DiscordEntity.getCreationTimestamp(du.id()).toEpochMilli());
				firstSeen = Math.min(firstSeen, du.firstSeen());
				lastSeen = Math.max(lastSeen, du.lastSeen());

				User user;

				if (server != null
						&& (user = server.getMemberById(du.id()).orElse(null)) != null) {
					lastSeen = currentTime;

					Instant curJoinTime = user.getJoinedAtTimestamp(server).orElse(null);
					if (curJoinTime != null) firstSeen = Math.min(firstSeen, curJoinTime.toEpochMilli());
				}

				discordUserList.append("\n#%d: ".formatted(++num));
				discordUserList.append(UserHandler.formatDiscordUser(du));
			}

			createdTime = Instant.ofEpochMilli(created);
			firstSeenTime = Instant.ofEpochMilli(firstSeen);
			lastSeenTime = Instant.ofEpochMilli(lastSeen);
		} else {
			createdTime = firstSeenTime = lastSeenTime = null;
		}

		Collection<ActionEntry> actions = ActionQueries.getActions(context.bot().getDatabase(), ActionType.Kind.USER, targetUserId);
		Collection<ActiveActionEntry> activeActions = ActionQueries.getActiveActions(context.bot().getDatabase(), ActionType.Kind.USER, targetUserId);

		StringBuilder firstPageSb = new StringBuilder();
		firstPageSb.append(String.format("**%d Discord Users:**%s\n"
				+ "**Sticky Name:** %s\n"
				+ "%s"
				+ "**Actions:** %d, %d active\n"
				+ "**Active Actions:** %s",
				userData.discordUsers().size(), discordUserList,
				userData.stickyName(),
				(createdTime != null ? formatTimes(createdTime, firstSeenTime, lastSeenTime, currentTime) : ""),
				actions.size(), activeActions.size(),
				(activeActions.isEmpty() ? "-" : activeActions.stream().map(a -> a.type().getId()).distinct().sorted().collect(Collectors.joining(", ")))));
		String firstThumbnail = null;
		int num = 0;

		if (firstDiscordUser != null) {
			firstPageSb.append("\n\n");
			firstPageSb.append(formatDiscordUser(++num, firstDiscordUser, currentTime, server, userData.discordUsers().size() > 1));

			firstThumbnail = getAvatarUrl(firstDiscordUser.id(), server);
		}

		if (userData.discordUsers().size() <= 1) {
			EmbedBuilder msg = new EmbedBuilder()
					.setTitle(title)
					.setDescription(firstPageSb.toString())
					.setTimestamp(Instant.ofEpochMilli(currentTime));
			if (firstThumbnail != null) msg.setThumbnail(firstThumbnail);

			context.channel().sendMessage(msg);
		} else {
			Paginator.Builder builder = new Paginator.Builder(context.user()).title(title);
			builder.page(new Page.Builder(firstPageSb).thumbnail(firstThumbnail).build());

			for (DiscordUserData discordUserData : userData.discordUsers()) {
				if (discordUserData == firstDiscordUser) continue;

				builder.page(new Page.Builder(formatDiscordUser(++num, discordUserData, currentTime, server, userData.discordUsers().size() > 1))
						.thumbnail(getAvatarUrl(discordUserData.id(), server))
						.build());
			}

			builder.buildAndSend(context.channel());
		}

		return true;
	}

	private static String formatTimes(Instant createdTime, Instant firstSeenTime, Instant lastSeenTime, long currentTime) {
		return String.format("**Created:** %s\n"
				+ "**First Seen:** %s\n"
				+ "**Last Seen:** %s\n",
				formatTime(createdTime, currentTime),
				formatTime(firstSeenTime, currentTime),
				formatTime(lastSeenTime, currentTime));
	}

	private static String formatDiscordUser(int num, DiscordUserData data, long currentTime, @Nullable Server server, boolean showAllTimes) {
		User user = server != null ? server.getMemberById(data.id()).orElse(null) : null;
		UserStatus status = user != null ? user.getStatus() : null;

		return String.format("**Discord User #%d**\n\n"
				+ "**ID:** `%s`\n"
				+ "**User:** %s#%s\n"
				+ "**Nick:** %s\n"
				+ "**Status:** %s\n"
				+ "%s"
				+ "**Roles:** %s",
				num,
				data.id(),
				data.username(), data.discriminator(),
				(data.nickname() != null ? data.nickname() : "-"),
				(status != null ? status.getStatusString() : "absent"),
				formatDiscordUserTimes(data, user, server, currentTime, showAllTimes),
				(user != null ? user.getRoles(server).stream().filter(r -> !r.isEveryoneRole()).map(Role::getMentionTag).collect(Collectors.joining(" ")) : "-"));
	}

	private static CharSequence formatDiscordUserTimes(DiscordUserData data, @Nullable User user, @Nullable Server server, long currentTime, boolean showAllTimes) {
		Instant createdTime = DiscordEntity.getCreationTimestamp(data.id());
		Instant joinTime = user != null ? user.getJoinedAtTimestamp(server).orElse(null) : null;
		Instant firstSeenTime = Instant.ofEpochMilli(data.firstSeen());
		Instant lastSeenTime = user != null ? Instant.ofEpochMilli(currentTime) : Instant.ofEpochMilli(data.lastSeen());

		if (joinTime != null && joinTime.isBefore(firstSeenTime)) {
			firstSeenTime = joinTime;
		}

		StringBuilder ret = new StringBuilder(200);

		if (showAllTimes) {
			ret.append(formatTimes(createdTime, firstSeenTime, lastSeenTime, currentTime));
		}

		if (joinTime != null) {
			ret.append(String.format("**Last Join:** %s\n", formatTime(joinTime, currentTime)));
		}

		return ret;
	}

	private static String formatTime(Instant time, long currentTime) {
		if (time == null) return "-";
		if (time.toEpochMilli() >= currentTime) return "now";

		return String.format("%s (%s ago)",
				FormatUtil.dateTimeFormatter.format(time),
				FormatUtil.formatDuration(currentTime - time.toEpochMilli(), 3));
	}

	private static String getAvatarUrl(long discordUserId, @Nullable Server server) {
		if (server == null) return null;

		User user = server.getMemberById(discordUserId).orElse(null);

		return user != null ? user.getAvatar().getUrl().toString() : null;
	}
}
