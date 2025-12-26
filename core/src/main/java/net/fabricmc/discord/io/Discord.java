package net.fabricmc.discord.io;

public interface Discord {
	GlobalEventHolder getGlobalEvents();

	Server getServer(long id);
	User getUser(long id, boolean fetch);
	User getYourself();

	void setActivity(String activity);
}
