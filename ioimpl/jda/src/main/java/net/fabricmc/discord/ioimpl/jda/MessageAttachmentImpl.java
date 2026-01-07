/*
 * Copyright (c) 2026 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
