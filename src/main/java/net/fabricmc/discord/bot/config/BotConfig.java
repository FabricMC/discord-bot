package net.fabricmc.discord.bot.config;

import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

public record BotConfig(Secrets secrets, GuildInfo guild, Modules modules, String commandPrefix) {
	@ConfigSerializable
	public record Secrets(String token, @Nullable String databaseUsername, @Nullable String databasePassword) {
		public Secrets {
			if (token == null) {
				throw new IllegalArgumentException("Token cannot be null!");
			}

			if (token.isEmpty()) {
				throw new IllegalArgumentException("Token value cannot be empty!");
			}
		}
	}

	@ConfigSerializable
	public record GuildInfo(Long id, Set<Long> ignoredChannels) {
		public GuildInfo {
			// FIXME: Make the guild id snowflake a primitive long: awaiting configurate to support primitive types properly for that.
			if (id == null) {
				throw new IllegalArgumentException("Guild id cannot be null!");
			}

			if (ignoredChannels.contains(null)) {
				throw new IllegalArgumentException("Ignored channels cannot contain any null");
			}
		}
	}

	@ConfigSerializable
	public record Modules(Set<String> disabled) {
	}
}
