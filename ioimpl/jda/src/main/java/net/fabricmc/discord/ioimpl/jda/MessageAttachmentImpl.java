package net.fabricmc.discord.ioimpl.jda;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import net.dv8tion.jda.api.entities.Message;

import net.fabricmc.discord.io.MessageAttachment;

public class MessageAttachmentImpl implements MessageAttachment {
	private final Message.Attachment wrapped;

	MessageAttachmentImpl(Message.Attachment wrapped) {
		this.wrapped = wrapped;
	}

	@Override
	public long getId() {
		return wrapped.getIdLong();
	}

	@Override
	public String getUrl() {
		return wrapped.getUrl();
	}

	@Override
	public String getFileName() {
		return wrapped.getFileName();
	}

	@Override
	public String getDescription() {
		return wrapped.getDescription();
	}

	@Override
	public int getSize() {
		return wrapped.getSize();
	}

	@Override
	public byte[] getBytes() {
		try (InputStream is = wrapped.getProxy().download().join()){
			return is.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static MessageAttachmentImpl wrap(Message.Attachment attachment) {
		if (attachment == null) return null;

		return new MessageAttachmentImpl(attachment);
	}
}
