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

package net.fabricmc.discord.bot.module.mapping;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.mappingio.tree.MappingTree.FieldMapping;

public final class YarnFieldCommand extends Command {
	private final MappingRepository repo;

	YarnFieldCommand(MappingRepository repo) {
		this.repo = repo;
	}

	@Override
	public String name() {
		return "yarnfield";
	}

	@Override
	public List<String> aliases() {
		return List.of("yf", "field");
	}

	@Override
	public String usage() {
		return "<fieldName> [latest | latestStable | <mcVersion>] [--ns=<nsList>] [--queryNs=<nsList>] [--displayNs=<nsList>] [--brief]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = MappingCommandUtil.getMcVersion(context, arguments);
		MappingData data = MappingCommandUtil.getMappingData(repo, mcVersion);
		String name = arguments.get("fieldName");

		boolean brief = arguments.containsKey("brief");

		List<String> queryNamespaces = MappingCommandUtil.getNamespaces(context, arguments, true);
		Collection<FieldMapping> results = data.findFields(name, data.resolveNamespaces(queryNamespaces, false));

		if (results.isEmpty()) {
			context.channel().sendMessage("no matches for the given field name, MC version and query namespace");
			return true;
		}

		List<String> namespaces = MappingCommandUtil.getNamespaces(context, arguments, false);

		Paginator.Builder builder = new Paginator.Builder(context.user())
				.title("%s matches", data.mcVersion)
				.footer("query ns: %s", String.join(",", queryNamespaces));

		StringBuilder sb = new StringBuilder(400);

		for (FieldMapping result : results) {
			if (brief) {
				for (String ns : namespaces) {
					String owner = result.getOwner().getName(ns);
					String member = result.getName(ns);

					if (owner != null && member != null) {
						sb.append(String.format("**%s:** `%s.%s`\n", FormatUtil.capitalize(ns), owner, member));
					}
				}
			} else {
				sb.append("**Class Names**\n\n");

				for (String ns : namespaces) {
					String res = result.getOwner().getName(ns);

					if (res != null) {
						sb.append(String.format("**%s:** `%s`\n", FormatUtil.capitalize(ns), res));
					}
				}

				sb.append("\n**Field Names**\n\n");

				for (String ns : namespaces) {
					String res = result.getName(ns);

					if (res != null) {
						sb.append(String.format("**%s:** `%s`\n", FormatUtil.capitalize(ns), res));
					}
				}

				sb.append(String.format("\n**Yarn Field Descriptor**\n\n```%3$s```\n"
								+ "**Yarn Access Widener**\n\n```accessible\tfield\t%1$s\t%2$s\t%3$s```\n"
								+ "**Yarn Mixin Target**\n\n```L%1$s;%2$s:%3$s```",
						result.getOwner().getName("yarn"),
						result.getName("yarn"),
						result.getDesc("yarn")));

				URI javadocUrl = data.getJavadocUrl(result);

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
