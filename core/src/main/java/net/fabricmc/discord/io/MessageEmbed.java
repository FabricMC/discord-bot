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

package net.fabricmc.discord.io;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface MessageEmbed {
	Type getType();

	enum Type {
		RICH("rich"), // generic embed rendered from embed attributes
		IMAGE("image"), // image embed
		VIDEO("video"), // video embed
		GIFV("gifv"), // animated gif image embed rendered as a video embed
		ARTICLE("article"), // article embed
		LINK("link"), // link embed
		POLL_RESULT("poll_result"),
		OTHER(null); // poll result embed

		private static final Map<String, Type> INDEX = new HashMap<>();
		public final String name;

		Type(String name) {
			this.name = name;
		}

		public static Type fromName(String name) {
			return INDEX.getOrDefault(name, OTHER);
		}

		static {
			for (Type type : values()) {
				if (type != OTHER) INDEX.put(type.name, type);
			}
		}
	}

	String getTitle();
	String getTitleUrl();
	String getDescription();
	String getFooterText();
	String getFooterIconUrl();
	record Field(String name, String value, boolean inline) { }
	List<Field> getFields();

	String getImageUrl();
	String getThumbnailUrl();
	Color getColor();

	String getAuthorName();
	String getAuthorUrl();
	String getAuthorIconUrl();
	Instant getTime();

	public static class Builder {
		private final Type type = Type.RICH;

		private String title;
		private String titleUrl;
		private String description;
		private String footerText;
		private String footerIconUrl;
		private final List<Field> fields = new ArrayList<>();

		private String imageUrl;
		private String thumbnailUrl;
		private Color color;

		private String authorName;
		private String authorUrl;
		private String authorIconUrl;
		private Instant time;

		public Builder title(String title) {
			return title(title, null);
		}

		public Builder title(String text, String url) {
			this.title = text;
			this.titleUrl = url;

			return this;
		}

		public Builder description(String description) {
			this.description = description;

			return this;
		}

		public Builder footer(String text) {
			return footer(text, null);
		}

		public Builder footer(String text, String iconUrl) {
			this.footerText = text;
			this.footerIconUrl = iconUrl;

			return this;
		}

		public Builder field(String name, String value, boolean inline) {
			this.fields.add(new Field(name, value, inline));

			return this;
		}

		public Builder image(String imageUrl) {
			this.imageUrl = imageUrl;

			return this;
		}

		public Builder thumbnail(String thumbnailUrl) {
			this.thumbnailUrl = thumbnailUrl;

			return this;
		}

		public Builder color(Color color) {
			this.color = color;

			return this;
		}

		public Builder author(String name, String url, String iconUrl) {
			this.authorName = name;
			this.authorUrl = url;
			this.authorIconUrl = iconUrl;

			return this;
		}

		public Builder time(Instant time) {
			this.time = time;

			return this;
		}

		public Builder timeNow() {
			this.time = Instant.now();

			return this;
		}

		public MessageEmbed build() {
			return new BuiltMessageEmbed(type,
					title, titleUrl, description,
					footerText, footerIconUrl,
					fields,
					imageUrl, thumbnailUrl, color,
					authorName, authorUrl, authorIconUrl,
					time);
		}
	}

	static class BuiltMessageEmbed implements MessageEmbed {
		private final Type type;

		private final String title;
		private final String titleUrl;
		private final String description;
		private final String footerText;
		private final String footerIconUrl;
		private final List<Field> fields;

		private final String imageUrl;
		private final String thumbnailUrl;
		private final Color color;

		private final String authorName;
		private final String authorUrl;
		private final String authorIconUrl;
		private final Instant time;

		BuiltMessageEmbed(Type type,
				String title, String titleUrl, String description,
				String footerText, String footerIconUrl,
				List<Field> fields,
				String imageUrl, String thumbnailUrl, Color color,
				String authorName, String authorUrl, String authorIconUrl,
				Instant time) {
			this.type = type;
			this.title = title;
			this.titleUrl = titleUrl;
			this.description = description;
			this.footerText = footerText;
			this.footerIconUrl = footerIconUrl;
			this.fields = fields;
			this.imageUrl = imageUrl;
			this.thumbnailUrl = thumbnailUrl;
			this.color = color;
			this.authorName = authorName;
			this.authorUrl = authorUrl;
			this.authorIconUrl = authorIconUrl;
			this.time = time;
		}

		@Override
		public Type getType() {
			return type;
		}

		@Override
		public String getTitle() {
			return title;
		}

		@Override
		public String getTitleUrl() {
			return titleUrl;
		}

		@Override
		public String getDescription() {
			return description;
		}

		@Override
		public String getFooterText() {
			return footerText;
		}

		@Override
		public String getFooterIconUrl() {
			return footerIconUrl;
		}

		@Override
		public List<Field> getFields() {
			return fields;
		}

		@Override
		public String getImageUrl() {
			return imageUrl;
		}

		@Override
		public String getThumbnailUrl() {
			return thumbnailUrl;
		}

		@Override
		public Color getColor() {
			return color;
		}

		@Override
		public String getAuthorName() {
			return authorName;
		}

		@Override
		public String getAuthorUrl() {
			return authorUrl;
		}

		@Override
		public String getAuthorIconUrl() {
			return authorIconUrl;
		}

		@Override
		public Instant getTime() {
			return time;
		}
	}
}
