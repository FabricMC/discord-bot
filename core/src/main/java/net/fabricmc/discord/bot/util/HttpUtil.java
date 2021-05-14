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

package net.fabricmc.discord.bot.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

public final class HttpUtil {
	private static final Duration timeout = Duration.ofSeconds(20);
	private static final HttpClient client = HttpClient.newBuilder()
			.followRedirects(Redirect.NORMAL)
			.connectTimeout(timeout)
			.build();

	public static HttpResponse<InputStream> makeRequest(String host, String path) throws URISyntaxException, IOException, InterruptedException {
		return makeRequest(host, path, null);
	}

	public static HttpResponse<InputStream> makeRequest(String host, String path, String query) throws URISyntaxException, IOException, InterruptedException {
		return makeRequest(new URI("https", null, host, -1, path, query, null));
	}

	public static HttpResponse<InputStream> makeRequest(URI uri) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.build();

		try {
			return client.send(request, BodyHandlers.ofInputStream());
		} catch (IOException e) {
			// retry once
			try {
				return client.send(request, BodyHandlers.ofInputStream());
			} catch (IOException f) { }

			throw e;
		}
	}
}
