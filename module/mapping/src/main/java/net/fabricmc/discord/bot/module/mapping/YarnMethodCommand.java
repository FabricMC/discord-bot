package net.fabricmc.discord.bot.module.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.MethodMapping;

public final class YarnMethodCommand extends Command {
	YarnMethodCommand(MappingRepository repo) {
		this.repo = repo;
	}

	@Override
	public String name() {
		return "yarnmethod";
	}

	@Override
	public List<String> aliases() {
		return List.of("ym");
	}

	@Override
	public String usage() {
		return "<methodName> [latest | latestStable | <mcVersion>]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = arguments.get("mcVersion");
		if (mcVersion == null) mcVersion = arguments.get("unnamed_1");

		MappingData data = YarnCommandUtil.getMappingData(repo, mcVersion);
		Collection<MethodMapping> results = data.findMethods(arguments.get("methodName"));

		if (results.isEmpty()) {
			context.channel().sendMessage("no matches");
			return true;
		}

		List<String> pages = new ArrayList<>();

		for (MethodMapping result : results) {
			pages.add(String.format("**%s matches**\n\n"
					+ "**Class Names**\n\n**Official:** `%s`\n**Intermediary:** `%s`\n**Yarn:** `%s`\n\n"
					+ "**Method Names**\n\n**Official:** `%s`\n**Intermediary:** `%s`\n**Yarn:** `%s`\n\n"
					+ "**Yarn Method Descriptor**\n\n```%s```\n\n"
					+ "**Yarn Access Widener**\n\n```accessible\tmethod\t%s\t%s\t%s```",
					data.mcVersion,
					result.getOwner().getName("official"),
					result.getOwner().getName("intermediary"),
					result.getOwner().getName("named"),
					result.getName("official"),
					result.getName("intermediary"),
					result.getName("named"),
					result.getDesc("named"),
					result.getOwner().getName("named"),
					result.getName("named"),
					result.getDesc("named")));
		}

		Paginator paginator = new Paginator(LogManager.getLogger("Commands"), pages, 200, context.author().getId());
		paginator.send(context.channel());

		return true;
	}

	private final MappingRepository repo;
}
