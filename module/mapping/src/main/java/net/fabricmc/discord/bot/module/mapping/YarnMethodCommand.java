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
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.MethodMapping;
import net.fabricmc.discord.bot.util.FormatUtil;

public final class YarnMethodCommand extends Command {
	private final MappingRepository repo;

	YarnMethodCommand(MappingRepository repo) {
		this.repo = repo;
	}

	@Override
	public String name() {
		return "yarnmethod";
	}

	@Override
	public List<String> aliases() {
		return List.of("ym", "method");
	}

	@Override
	public String usage() {
		return "<methodName> [latest | latestStable | <mcVersion>] [--ns=<nsList>] [--queryNs=<nsList>] [--displayNs=<nsList>]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = MappingCommandUtil.getMcVersion(context, arguments);
		MappingData data = MappingCommandUtil.getMappingData(repo, mcVersion);
		String name = arguments.get("methodName");

		List<String> queryNamespaces = MappingCommandUtil.getNamespaces(context, arguments, true);
		Collection<MethodMapping> results = data.findMethods(name, data.resolveNamespaces(queryNamespaces, false));

		if (results.isEmpty()) {
			context.channel().sendMessage("no matches for the given method name, MC version and query namespace");
			return true;
		}

		List<String> namespaces = MappingCommandUtil.getNamespaces(context, arguments, false);

		Paginator.Builder builder = new Paginator.Builder(context.user())
				.title("%s matches", data.mcVersion)
				.footer("query ns: %s", String.join(",", queryNamespaces));

		StringBuilder sb = new StringBuilder(400);

		for (MethodMapping result : results) {
			sb.append("**Class Names**\n\n");

			for (String ns : namespaces) {
				String res = result.getOwner().getName(ns);

				if (res != null) {
					sb.append(String.format("**%s:** `%s`\n", FormatUtil.capitalize(ns), res));
				}
			}

			sb.append("\n**Method Names**\n\n");

			for (String ns : namespaces) {
				String res = result.getName(ns);

				if (res != null) {
					sb.append(String.format("**%s:** `%s`\n", FormatUtil.capitalize(ns), result.getName(ns)));
				}
			}

			sb.append(String.format("\n**Yarn Method Descriptor**\n\n```%s```\n\n"
					+ "**Yarn Access Widener**\n\n```accessible\tmethod\t%s\t%s\t%s```",
					result.getDesc("yarn"),
					result.getOwner().getName("yarn"),
					result.getName("yarn"),
					result.getDesc("yarn")));

			builder.page(sb);
			sb.setLength(0);
		}

		builder.buildAndSend(context.channel());

		return true;
	}
}
