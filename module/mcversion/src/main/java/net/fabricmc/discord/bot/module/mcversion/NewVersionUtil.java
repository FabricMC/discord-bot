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

package net.fabricmc.discord.bot.module.mcversion;

import java.util.Locale;
import java.util.regex.Matcher;

public class NewVersionUtil {
	public enum VersionType {
		SNAPSHOT("snapshot"),
		PRE_RELEASE("pre"),
		RELEASE_CANDIDATE("rc"),
		FULL_RELEASE("");

		public final String name;

		VersionType(String name) {
			this.name = name;
		}

		static VersionType fromName(String name) {
			switch (name.toLowerCase(Locale.ROOT)) {
				case "snapshot":
					return SNAPSHOT;
				case "pre":
				case "pre-release":
				case "prerelease":
					return PRE_RELEASE;
				case "rc":
				case "release candidate":
					return RELEASE_CANDIDATE;
				default:
					return FULL_RELEASE;
			}
		}
	}

	public record Version(int year, int drop, int hotfix, VersionType type, int build) implements Comparable<Version> {
		public static final Version DEFAULT = new Version(26, 1, 0, VersionType.SNAPSHOT, 0);

		public static Version get(Matcher matcher) {
			int yearN = Integer.parseUnsignedInt(matcher.group(1));
			int dropN = Integer.parseUnsignedInt(matcher.group(2));
			String hotfix = matcher.group(3);
			int hotfixN = hotfix == null ? 0 : Integer.parseUnsignedInt(hotfix);
			if (matcher.group(4) == null) {
				return new Version(yearN, dropN, hotfixN, VersionType.FULL_RELEASE, 0);
			}
			return new Version(yearN, dropN, hotfixN, VersionType.fromName(matcher.group(4)), Integer.parseUnsignedInt(matcher.group(5)));
		}

		@Override
		public String toString() {
			if (this.hotfix == 0) {
				if (this.type == VersionType.FULL_RELEASE) {
					return "%d.%d".formatted(this.year, this.drop);
				} else {
					return "%d.%d-%s-%d".formatted(this.year, this.drop, this.type.name, this.build);
				}
			} else {
				if (this.type == VersionType.FULL_RELEASE) {
					return "%d.%d.%d".formatted(this.year, this.drop, this.hotfix);
				} else {
					return "%d.%d.%d-%s-%d".formatted(this.year, this.drop, this.hotfix, this.type.name, this.build);
				}
			}
		}

		@Override
		public int compareTo(Version o) {
			if (this.year != o.year) {
				return this.year - o.year;
			} else if (this.drop != o.drop) {
				return this.drop - o.drop;
			} else if (this.type != o.type) {
				return this.type.compareTo(o.type);
			} else {
				return this.build - o.build;
			}
		}
	}
}
