package net.fabricmc.discord.bot.module.mapping.mappinglib;

public enum MappedElementKind {
	CLASS(0),
	FIELD(1),
	METHOD(1),
	METHOD_ARG(2),
	METHOD_VAR(2);

	MappedElementKind(int level) {
		this.level = level;
	}

	public final int level;
}