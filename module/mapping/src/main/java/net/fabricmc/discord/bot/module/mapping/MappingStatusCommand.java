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

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.javacord.api.entity.message.embed.EmbedBuilder;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.module.mcversion.McVersionRepo;
import net.fabricmc.mappingio.tree.MappingTree;

public final class MappingStatusCommand extends Command {
	private final MappingRepository repo;

	MappingStatusCommand(MappingRepository repo) {
		this.repo = repo;
	}

	@Override
	public String name() {
		return "mappingstatus";
	}

	@Override
	public String usage() {
		return "[latest | latestStable | <mcVersion>]";
	}

	@Override
	public String permission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		if (!arguments.containsKey("unnamed_0") && !arguments.containsKey("mcVersion")) {
			Collection<String> versions = repo.getLoadedVersions();
			context.channel().sendMessage("Loaded %d versions: %s".formatted(versions.size(), versions.stream().sorted().collect(Collectors.joining(", "))));
		} else {
			String mcVersion = arguments.get("mcVersion");
			if (mcVersion == null) mcVersion = arguments.get("unnamed_0");
			mcVersion = McVersionRepo.get(context.bot()).resolve(context, mcVersion);
			MappingData data = MappingCommandUtil.getMappingData(repo, mcVersion);
			MappingTree tree = data.mappingTree;

			context.channel().sendMessage(new EmbedBuilder()
					.setTitle("%s data".formatted(mcVersion))
					.setDescription(String.format("**Namespaces:** %s, %s\n"
							+ "**Classes:** %d\n"
							+ "**Yarn version:** %s\n"
							+ "**MCP version:** %s\n"
							+ "**Javadoc available:** %s",
							tree.getSrcNamespace(), String.join(", ", tree.getDstNamespaces()),
							tree.getClasses().size(),
							(data.yarnMavenId != null ? data.yarnMavenId.substring(data.yarnMavenId.lastIndexOf(':') + 1) : "-"),
							(data.mcpVersion != null ? data.mcpVersion : "-"),
							(data.hasYarnJavadoc ? "yes" : "no")))
					.setTimestampToNow());
		}

		return true;
	}
}
