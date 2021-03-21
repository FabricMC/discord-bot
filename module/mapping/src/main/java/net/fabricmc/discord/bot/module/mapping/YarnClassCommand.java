package net.fabricmc.discord.bot.module.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.ClassMapping;

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
		return List.of("yc");
	}

	@Override
	public String usage() {
		return "<className> [latest | latestStable | <mcVersion>]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = arguments.get("mcVersion");
		if (mcVersion == null) mcVersion = arguments.get("unnamed_1");

		MappingData data = YarnCommandUtil.getMappingData(repo, mcVersion);
		Collection<ClassMapping> results = data.findClasses(arguments.get("className"));

		if (results.isEmpty()) {
			context.channel().sendMessage("no matches");
			return true;
		}

		List<String> pages = new ArrayList<>();

		for (ClassMapping result : results) {
			pages.add(String.format("**%s matches**\n\n"
					+ "**Names**\n\n**Official:** `%s`\n**Intermediary:** `%s`\n**Yarn:** `%s`\n\n"
					+ "**Yarn Access Widener**\n\n```accessible\tclass\t%s```",
					data.mcVersion,
					result.getName("official"),
					result.getName("intermediary"),
					result.getName("named"),
					result.getName("named")));
		}

		Paginator paginator = new Paginator(LogManager.getLogger("Commands"), pages, 200, context.author().getId());
		paginator.send(context.channel());

		return true;
	}
}
