package net.fabricmc.discord.bot.module.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.FieldMapping;

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
		return List.of("yf");
	}

	@Override
	public String usage() {
		return "<fieldName> [latest | latestStable | <mcVersion>]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = arguments.get("mcVersion");
		if (mcVersion == null) mcVersion = arguments.get("unnamed_1");

		MappingData data = YarnCommandUtil.getMappingData(repo, mcVersion);
		Collection<FieldMapping> results = data.findFields(arguments.get("fieldName"));

		if (results.isEmpty()) {
			context.channel().sendMessage("no matches");
			return true;
		}

		List<String> pages = new ArrayList<>();

		for (FieldMapping result : results) {
			pages.add(String.format("**%s matches**\n\n"
					+ "**Class Names**\n\n**Official:** `%s`\n**Intermediary:** `%s`\n**Yarn:** `%s`\n\n"
					+ "**Field Names**\n\n**Official:** `%s`\n**Intermediary:** `%s`\n**Yarn:** `%s`\n\n"
					+ "**Yarn Field Descriptor**\n\n```%s```\n\n"
					+ "**Yarn Access Widener**\n\n```accessible\tfield\t%s\t%s\t%s```",
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
}
