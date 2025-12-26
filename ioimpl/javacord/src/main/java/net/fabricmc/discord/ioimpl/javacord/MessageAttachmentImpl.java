package net.fabricmc.discord.ioimpl.javacord;

import static net.fabricmc.discord.ioimpl.javacord.DiscordProviderImpl.urlToString;

import net.fabricmc.discord.io.MessageAttachment;

public class MessageAttachmentImpl implements MessageAttachment {
	private final org.javacord.api.entity.message.MessageAttachment wrapped;

	MessageAttachmentImpl(org.javacord.api.entity.message.MessageAttachment wrapped) {
		this.wrapped = wrapped;
	}

	@Override
	public long getId() {
		return wrapped.getId();
	}

	@Override
	public String getUrl() {
		return urlToString(wrapped.getUrl());
	}

	@Override
	public String getFileName() {
		return wrapped.getFileName();
	}

	@Override
	public String getDescription() {
		return wrapped.getDescription().orElse(null);
	}

	@Override
	public int getSize() {
		return wrapped.getSize();
	}

	@Override
	public byte[] getBytes() {
		return wrapped.asByteArray().join();
	}

	static MessageAttachmentImpl wrap(org.javacord.api.entity.message.MessageAttachment attachment) {
		if (attachment == null) return null;

		return new MessageAttachmentImpl(attachment);
	}
}
