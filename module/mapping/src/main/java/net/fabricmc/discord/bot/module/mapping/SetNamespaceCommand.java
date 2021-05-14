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

package net.fabricmc.discord.bot.module.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.message.reaction.ReactionAddEvent;
import org.javacord.api.listener.message.reaction.ReactionAddListener;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.util.CommonEmotes;
import net.fabricmc.discord.bot.util.DiscordUtil;

public final class SetNamespaceCommand extends Command {
	private final NamespaceApplication subType;

	SetNamespaceCommand(NamespaceApplication subType) {
		this.subType = subType;
	}

	@Override
	public String name() {
		return String.format("set%sNamespace", subType.subName);
	}

	@Override
	public List<String> aliases() {
		String alias = String.format("set%sNs", subType.subName);

		if (subType.shortSubName == null) {
			return List.of(alias);
		} else {
			return List.of(alias, String.format("set%sNs", subType.shortSubName));
		}
	}

	@Override
	public String usage() {
		return "[reset|<nsList>]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String nsList = arguments.get("nsList");

		if (nsList != null) {
			Collection<String> privateNs = new ArrayList<>();

			if (subType.isDisplay) {
				List<String> ns = MappingCommandUtil.getNamespaces(context, nsList, false, false);
				setUserConfig(context, MappingModule.DISPLAY_NAMESPACES, ns);
				privateNs.addAll(MappingCommandUtil.getPrivateNamespaces(context, ns));
			}

			if (subType.isQuery) {
				List<String> ns = MappingCommandUtil.getNamespaces(context, nsList, true, false);
				setUserConfig(context, MappingModule.QUERY_NAMESPACES, ns);
				privateNs.addAll(MappingCommandUtil.getPrivateNamespaces(context, ns));
			}

			if (!privateNs.isEmpty()) {
				privateNs = new LinkedHashSet<>(privateNs);
				context.channel().sendMessage("Settings updated, bot DM only: "+String.join(", ", privateNs));
			} else {
				context.channel().sendMessage("Settings updated");
			}
		} else if ("reset".equals(arguments.get("unnamed_0"))) {
			if (subType.isDisplay) {
				removeUserConfig(context, MappingModule.DISPLAY_NAMESPACES);
			}

			if (subType.isQuery) {
				removeUserConfig(context, MappingModule.QUERY_NAMESPACES);
			}

			context.channel().sendMessage("Settings reset");
		} else {
			new InteractiveMsg(context, subType).post(false);
		}

		return true;
	}

	enum NamespaceApplication {
		ALL("", null, true, true),
		DISPLAY("Display", "D", true, false),
		QUERY("Query", "Q", false, true);

		NamespaceApplication(String subName, String shortSubName, boolean isDisplay, boolean isQuery) {
			this.subName = subName;
			this.shortSubName = shortSubName;
			this.isDisplay = isDisplay;
			this.isQuery = isQuery;
		}

		public final String subName;
		public final String shortSubName;
		public final boolean isDisplay;
		public final boolean isQuery;
	}

	static final class InteractiveMsg implements ReactionAddListener {
		private final CommandContext context;
		private final NamespaceApplication application;
		private volatile Message message;

		InteractiveMsg(CommandContext context, NamespaceApplication application) {
			this.context = context;
			this.application = application;
		}

		public void post(boolean error) {
			context.channel().sendMessage(getEmbed(error))
			.thenCompose(msg -> {
				this.message = msg;
				List<CompletableFuture<Void>> futures = new ArrayList<>(MappingModule.supportedNamespaces.size() + 1);

				for (int i = 0; i < MappingModule.supportedNamespaces.size(); i++) {
					futures.add(msg.addReactions(CommonEmotes.DIGITS[i + 1]));
				}

				if (DiscordUtil.canRemoveReactions(message.getChannel())) {
					futures.add(msg.addReaction(CommonEmotes.X));
				}

				return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
						.thenAccept(ignore -> {
							msg.addReactionAddListener(this).removeAfter(200, TimeUnit.SECONDS).addRemoveHandler(this::destroy);
						});
			})
			.exceptionally(exc -> {
				exc.printStackTrace();
				return null;
			});
		}

		private EmbedBuilder getEmbed(boolean error) {
			StringBuilder supported = new StringBuilder();
			int i = 0;

			for (String ns : MappingModule.supportedNamespaces) {
				if (supported.length() > 0) supported.append(' ');
				supported.append(CommonEmotes.DIGITS[i + 1]);
				supported.append(' ');
				supported.append(ns);
				i++;
			}

			List<String> selDisplayNs = application.isDisplay ? MappingCommandUtil.getConfiguredNamespaces(context, false) : null;
			List<String> selQueryNs = application.isQuery ? MappingCommandUtil.getConfiguredNamespaces(context, true) : null;
			List<String> selectedNs = mergeNs(selDisplayNs, selQueryNs);

			StringBuilder selected = new StringBuilder();

			for (String ns : selectedNs) {
				if (selected.length() > 0) selected.append(' ');
				selected.append(CommonEmotes.DIGITS[MappingModule.supportedNamespaces.indexOf(ns) + 1]);
				selected.append(' ');
				selected.append(ns);

				if (application.isDisplay && application.isQuery) {
					if (!selDisplayNs.contains(ns)) {
						selected.append(" (q)");
					} else if (!selQueryNs.contains(ns)) {
						selected.append(" (d)");
					}
				}
			}

			List<String> privateNs = MappingCommandUtil.getPrivateNamespaces(context, selectedNs);
			String privateSuffix = privateNs.isEmpty() ? "" : "\nBot DM only: "+String.join(", ", privateNs);

			String errorSuffix = error ? "\n\n**Error: Can't remove all namespaces!**" : "";

			String description = String.format("**Supported:** %s\n"
					+ "**Selected:** %s\n\n"
					+ "Use reactions to select/deselect, at least one has to remain selected.%s%s",
					supported, selected, privateSuffix, errorSuffix);

			return new EmbedBuilder()
					.setTitle("%sNamespace Config".formatted(application.subName.isEmpty() ? "" : application.subName.concat(" ")))
					.setDescription(description);
		}

		private void destroy() {
			Message message = this.message;

			if (message != null) {
				this.message = null;
				message.removeListener(ReactionAddListener.class, this);

				if (DiscordUtil.canRemoveReactions(message.getChannel())) {
					message.removeAllReactions();
				}

				//message.delete("interactive message destroyed");
			}
		}

		@Override
		public void onReactionAdd(ReactionAddEvent event) {
			if (event.getUserId() != context.author().getId()) {
				return;
			}

			Emoji emoji = event.getEmoji();
			String emojiStr;

			if (emoji.equalsEmoji(CommonEmotes.X)) {
				destroy();
				return;
			} else if ((emojiStr = emoji.asUnicodeEmoji().orElse(null)) != null) {
				for (int i = 0; i < MappingModule.supportedNamespaces.size(); i++) {
					if (!emojiStr.equals(CommonEmotes.DIGITS[i + 1])) continue;

					String ns = MappingModule.supportedNamespaces.get(i);

					List<String> selDisplayNs = application.isDisplay ? MappingCommandUtil.getConfiguredNamespaces(context, false) : null;
					List<String> selQueryNs = application.isQuery ? MappingCommandUtil.getConfiguredNamespaces(context, true) : null;

					boolean remove = (selDisplayNs == null || selDisplayNs.contains(ns)) && (selQueryNs == null || selQueryNs.contains(ns));

					List<String> newDisplayNs = toggleNs(selDisplayNs, ns, remove);
					List<String> newQueryNs = toggleNs(selQueryNs, ns, remove);

					boolean error = application.isDisplay && newDisplayNs == null || application.isQuery && newQueryNs == null;

					if (!error) {
						if (application.isDisplay) setUserConfig(context, MappingModule.DISPLAY_NAMESPACES, newDisplayNs);
						if (application.isQuery) setUserConfig(context, MappingModule.QUERY_NAMESPACES, newQueryNs);
					}

					Message msg = this.message;

					if (msg != null) {
						CompletableFuture<?> future;

						if (DiscordUtil.canRemoveReactions(event.getChannel())) {
							future = msg.edit(getEmbed(error));
						} else {
							future = msg.delete().thenRun(() -> post(error));
						}

						future.exceptionally(exc -> {
							exc.printStackTrace();
							return null;
						});
					}

					break;
				}
			}

			if (DiscordUtil.canRemoveReactions(event.getChannel())) { // requires the user to remove reactions manually (double click to advance)
				event.removeReaction()
				.exceptionally(e -> {
					e.printStackTrace();
					return null;
				});
			}
		}

		private static List<String> mergeNs(List<String> a, List<String> b) {
			if (a == null) {
				return b;
			} else if (b == null) {
				return a;
			} else {
				Set<String> tmp = new HashSet<>(a);
				tmp.addAll(b);

				List<String> ret = new ArrayList<>(tmp);
				ret.sort(Comparator.comparing(e -> MappingModule.supportedNamespaces.indexOf(e)));

				return ret;
			}
		}

		private static List<String> toggleNs(List<String> list, String ns, boolean remove) {
			if (list == null) return null;

			if (remove) {
				if (list.contains(ns)) {
					if (list.size() == 1) return null;

					list = new ArrayList<>(list);
					list.remove(ns);
				}
			} else if (!list.contains(ns)) { // add
				list = new ArrayList<>(list);
				list.add(ns);
				list.sort(Comparator.comparing(e -> MappingModule.supportedNamespaces.indexOf(e)));
			}

			return list;
		}
	}
}
