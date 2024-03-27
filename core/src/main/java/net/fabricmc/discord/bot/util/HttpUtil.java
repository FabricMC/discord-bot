/*
 * Copyright (c) 2021, 2022 FabricMC
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

package net.fabricmc.discord.bot.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import org.apache.logging.log4j.Logger;

public final class HttpUtil {
	private static final Duration timeout = Duration.ofSeconds(20);
	private static final HttpClient client = HttpClient.newBuilder()
			.followRedirects(Redirect.NORMAL)
			.connectTimeout(timeout)
			.build();

	public static URI toUri(String host, String path) throws URISyntaxException {
		return toUri(host, path, null);
	}

	public static URI toUri(String host, String path, String query) throws URISyntaxException {
		return new URI("https", null, host, -1, path, query, null);
	}

	public static HttpResponse<InputStream> makeRequest(URI uri) throws IOException, InterruptedException {
		return makeRequest(uri, Collections.emptyMap());
	}

	public static HttpResponse<InputStream> makeRequest(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(timeout);

		for (Map.Entry<String, String> entry : headers.entrySet()) {
			builder.header(entry.getKey(), entry.getValue());
		}

		HttpRequest request = builder.build();

		try {
			return client.send(request, BodyHandlers.ofInputStream());
		} catch (IOException e) {
			// retry once
			try {
				return client.send(request, BodyHandlers.ofInputStream());
			} catch (IOException f) { }

			throw new HttpException(uri, e);
		}
	}

	public static void logError(String desc, Throwable exc, Logger logger) {
		if (exc instanceof HttpException) {
			desc = String.format("%s: Error requesting %s", desc, ((HttpException) exc).uri);
			exc = exc.getCause();
		}

		if (exc instanceof HttpTimeoutException
				|| exc instanceof ConnectException) {
			logger.warn("{}: {}", desc, exc.toString());
		} else {
			logger.warn("{}", desc, exc);
		}
	}

	@SuppressWarnings("serial")
	private static final class HttpException extends IOException {
		HttpException(URI uri, Throwable exc) {
			super("Error requesting "+uri, exc);

			this.uri = uri;
		}

		final URI uri;
	}
}
