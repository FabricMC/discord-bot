package net.fabricmc.discord.bot.module.mapping;

import net.fabricmc.discord.bot.command.CommandException;

final class YarnCommandUtil {
	public static MappingData getMappingData(MappingRepository repo, String mcVersion) throws CommandException {
		MappingData data;

		if (mcVersion == null || mcVersion.equals("latestStable")) {
			data = repo.getLatestStableMcMappingData();
		} else if (mcVersion.equals("latest")) {
			data = repo.getLatestMcMappingData();
		} else {
			data = repo.getMappingData(mcVersion);
		}

		if (data == null) throw new CommandException("Invalid/unavailable MC version");

		return data;
	}
}
