/*
 * Copyright (c) 2021, 2023 FabricMC
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

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;

public final class YarnClassCommand extends Command {
	private final MappingRepository repo;

	YarnClassCommand(MappingRepository repo) {
		this.repo = repo;
	}

	@Override
	public String name() {
		return "yarnclass";
	}

	@Override
	public List<String> aliases() {
		return List.of("yc", "class");
	}

	@Override
	public String usage() {
		return "<className> [latest | latestStable | <mcVersion>] [--ns=<nsList>] [--queryNs=<nsList>] [--displayNs=<nsList>] [--brief]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = MappingCommandUtil.getMcVersion(context, arguments);
		MappingData data = MappingCommandUtil.getMappingData(repo, mcVersion);
		String name = arguments.get("className");

		boolean briefMappings = MappingCommandUtil.shouldShowBriefMappings(context, arguments);

		List<String> queryNamespaces = MappingCommandUtil.getNamespaces(context, arguments, true);
		Collection<ClassMapping> results = data.findClasses(name, data.resolveNamespaces(queryNamespaces, false));

		if (results.isEmpty()) {
			context.channel().sendMessage("no matches for the given class name, MC version and query namespace");
			return true;
		}

		List<String> namespaces = MappingCommandUtil.getNamespaces(context, arguments, false);

		Paginator.Builder builder = new Paginator.Builder(context.user())
				.title("%s matches", data.mcVersion)
				.footer("query ns: %s", String.join(",", queryNamespaces));

		StringBuilder sb = new StringBuilder(400);

		for (ClassMapping result : results) {
			if (!briefMappings)
				sb.append("**Names**\n\n");

			URI javadocUrl = data.getJavadocUrl(result);

			for (String ns : namespaces) {
				String res = result.getName(ns);

				boolean linkJavadoc = briefMappings && ns.equals("yarn") && javadocUrl != null;

				if (res != null) {
					if (linkJavadoc)
						sb.append('[');
					sb.append(String.format("**%s:** `%s`\n", FormatUtil.capitalize(ns), res));

					if (linkJavadoc)
						sb.append(String.format("](%s)", javadocUrl));
				}
			}

			if (!briefMappings) {
				sb.append(String.format("\n**Yarn Access Widener**\n\n```accessible\tclass\t%s```",
						result.getName("yarn")));

				if (javadocUrl != null) {
					sb.append(String.format("\n**[Javadoc](%s)**", javadocUrl));
				}
			}

			builder.page(sb);
			sb.setLength(0);
		}

		builder.buildAndSend(context.channel());

		return true;
	}
}
