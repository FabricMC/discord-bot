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
import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.discord.io.MessageAttachment;

public final class CachedMessageAttachment {
	CachedMessageAttachment(MessageAttachment attachment) {
		this.id = attachment.getId();
		this.url = attachment.getUrl().toString();
		this.fileName = attachment.getFileName();
		this.approximateSize = attachment.getSize(); // discord sometimes reports the wrong size
		this.size = -1;
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

	public int getApproximateSize() {
		int ret = size;

		return ret >= 0 ? ret : approximateSize; // use most precise size immediately available
	}

	public int getSize(boolean cacheContent) {
		int ret = size;
		if (ret >= 0) return ret;

		try {
			byte[] data = getData(cacheContent);
			if (data != null) return data.length;
		} catch (IOException | InterruptedException | URISyntaxException e) {
			e.printStackTrace();
		}

		return approximateSize;
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
			int size = this.size;
			boolean sizeIsApproximate = size < 0;
			if (sizeIsApproximate) size = approximateSize;

			ret = new byte[size];
			int offset = 0;
			int len;

			while ((len = is.read(ret, offset, ret.length - offset)) >= 0) {
				offset += len;

				if (offset == ret.length) {
					int test = is.read();

					if (test != -1) {
						if (SIZE_TOLERANCE == 0
								|| !sizeIsApproximate
								|| ret.length >= size + SIZE_TOLERANCE) {
							throw new IOException(String.format("content size (%d+) exceeds recorded size (%d)", offset + 1 + is.available(), size));
						}

						ret = Arrays.copyOf(ret, ret.length + SIZE_TOLERANCE);
						ret[offset++] = (byte) test;
					} else {
						break;
					}
				}
			}

			if (offset < ret.length) {
				if (ret.length < size - SIZE_TOLERANCE) {
					throw new IOException(String.format("content size (%d) below recorded size (%d)", offset, size));
				}

				ret = Arrays.copyOf(ret, offset);
			}

			if (cache) data = ret;
			this.size = ret.length;

			return ret;
		} catch (IOException e) {
			throw new IOException("error fetching "+url, e);
		}
	}

	private static final int SIZE_TOLERANCE = 128;

	private final long id;
	private final String url;
	private final String fileName;
	private final int approximateSize;
	private volatile int size;
	private volatile byte[] data;
}
