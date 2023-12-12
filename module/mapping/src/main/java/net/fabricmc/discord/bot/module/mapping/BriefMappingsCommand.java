package net.fabricmc.discord.bot.module.mapping;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;

import java.util.Map;

public class BriefMappingsCommand extends Command {
    @Override
    public String name() {
        return "briefMappings";
    }

    @Override
    public String usage() {
        return "reset | true | false";
    }

    @Override
    public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
        String arg = arguments.get("unnamed_0");

        switch (arg) {
            case "reset":
                removeUserConfig(context, MappingModule.BRIEF_MAPPINGS);
                context.channel().sendMessage("Default brief mappings reset");
                break;
            case "true":
                setUserConfig(context, MappingModule.BRIEF_MAPPINGS, true);
                context.channel().sendMessage("Default brief mappings enabled");
                break;
            case "false":
                setUserConfig(context, MappingModule.BRIEF_MAPPINGS, false);
                context.channel().sendMessage("Default brief mappings disabled");
                break;
            default:
                throw new CommandException("Invalid argument %s", arg);
        }

        return false;
    }
}
