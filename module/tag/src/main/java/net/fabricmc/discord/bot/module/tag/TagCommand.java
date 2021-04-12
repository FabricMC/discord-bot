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

package net.fabricmc.discord.bot.module.tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;

final class TagCommand extends Command {
	private final TagModule handler;

	TagCommand(TagModule handler) {
		this.handler = handler;
	}

	@Override
	public String name() {
		return "tag";
	}

	@Override
	public List<String> aliases() {
		return List.of("tags");
	}

	@Override
	public String usage() {
		return "";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		List<TagInstance> tags = new ArrayList<>(handler.getTags());
		tags.sort(Comparator.comparing(TagInstance::getName));

		Paginator.Builder builder = new Paginator.Builder(context.author()).title("Tag list");
		StringBuilder currentPage = new StringBuilder();
		String cmdPrefix = handler.getBot().getCommandPrefix();
		currentPage.append(String.format("Post a tag with `%s%s<tagName>` and arguments as required.\nSpecifying the base name is enough when unique.\n\n",
				cmdPrefix, cmdPrefix));

		int pos = 0;

		for (TagInstance tag : tags) {
			if (pos % 20 == 0 && pos != 0) {
				builder.page(currentPage);
				currentPage.setLength(0);;
			}

			pos++;
			currentPage.append(String.format("`%s", tag.getName()));

			for (int i = 0; i < tag.getArgCount(); i++) {
				currentPage.append(String.format(" <%d>", i));
			}

			currentPage.append("`\n");
		}

		if (currentPage.length() > 0) {
			builder.page(currentPage);
		}

		builder.buildAndSend(context.channel());

		return true;
	}
}
