package net.fabricmc.discord.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public interface MessageAttachment {
	long getId();
	String getUrl();
	String getFileName();
	String getDescription();
	int getSize();
	byte[] getBytes();

	// for built use

	default boolean hasBytesReady() {
		return true;
	}

	default InputStream getInputStream() {
		return new ByteArrayInputStream(getBytes());
	}

	public static class Builder {
		private InputStream is;
		private byte[] data;
		private String fileName;
		private String description;

		public Builder data(byte[] data) {
			this.data = data;

			return this;
		}

		public Builder data(InputStream is) {
			this.is = is;

			return this;
		}

		public Builder data(Path file) {
			try {
				this.is = Files.newInputStream(file, StandardOpenOption.READ);
				if (fileName == null) fileName = file.getFileName().toString();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			return this;
		}

		public Builder data(URL url) {
			try {
				this.is = url.openStream();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			return this;
		}

		public Builder name(String fileName) {
			this.fileName = fileName;

			return this;
		}

		public Builder description(String description) {
			this.description = description;

			return this;
		}

		public MessageAttachment build() {
			return new BuiltMessageAttachment(is, data, fileName, description);
		}
	}

	static class BuiltMessageAttachment implements MessageAttachment {
		private final InputStream is;
		private byte[] data;
		private final String fileName;
		private final String description;

		BuiltMessageAttachment(InputStream is, byte[] data, String fileName, String description) {
			this.is = is;
			this.data = null;
			this.fileName = fileName;
			this.description = description;
		}

		@Override
		public long getId() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String getUrl() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String getFileName() {
			return fileName;
		}

		@Override
		public String getDescription() {
			return description;
		}

		@Override
		public int getSize() {
			return getBytes().length;
		}

		@Override
		public boolean hasBytesReady() {
			return data != null;
		}

		@Override
		public byte[] getBytes() {
			if (data == null) {
				try {
					data = is.readAllBytes();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}

			return data;
		}

		@Override
		public InputStream getInputStream() {
			return data != null ? new ByteArrayInputStream(data) : is;
		}
	}

}
