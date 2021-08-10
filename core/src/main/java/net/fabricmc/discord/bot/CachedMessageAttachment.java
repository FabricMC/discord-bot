/*
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.discord.bot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;

import org.javacord.api.entity.message.MessageAttachment;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.HttpUtil;

public final class CachedMessageAttachment {
	CachedMessageAttachment(MessageAttachment attachment) {
		this.id = attachment.getId();
		this.url = attachment.getUrl().toString();
		this.fileName = attachment.getFileName();
		this.size = attachment.getSize();
	}

	public long getId() {
		return id;
	}

	public String getUrl() {
		return url;
	}

	public String getFileName() {
		return fileName;
	}

	public int getSize() {
		return size;
	}

	public boolean hasDataCached() {
		return data != null;
	}

	public @Nullable byte[] getData(boolean cache) throws IOException, InterruptedException, URISyntaxException {
		byte[] ret = data;
		if (ret != null) return ret;

		HttpResponse<InputStream> response = HttpUtil.makeRequest(new URI(url));

		if (response.statusCode() != 200) {
			response.body().close();
			return null;
		}

		try (InputStream is = response.body()) {
			ret = new byte[size];
			int offset = 0;
			int len;

			while ((len = is.read(ret, offset, ret.length - offset)) >= 0) {
				offset += len;

				if (offset == ret.length) {
					int test = is.read();
					if (test != -1) throw new IOException("content size exceeds recorded size");
					break;
				}
			}

			if (offset < ret.length) throw new IOException("content size below recorded size");

			if (cache) data = ret;

			return ret;
		}
	}

	private final long id;
	private final String url;
	private final String fileName;
	private final int size;
	private volatile byte[] data;
}
