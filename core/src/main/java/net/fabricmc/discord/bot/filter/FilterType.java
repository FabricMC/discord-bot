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

package net.fabricmc.discord.bot.filter;

import java.util.Locale;
import java.util.regex.Pattern;

import org.javacord.api.entity.message.Message;

public enum FilterType {
	CONTENT("content") {
		@Override
		public MessageMatcher compile(String pattern) {
			String s = pattern.toLowerCase(Locale.ENGLISH);
			return (msg, lcContent) -> lcContent.contains(s);
		}
	},
	REGEX("regex") {
		@Override
		public MessageMatcher compile(String pattern) {
			Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
			return (msg, lcContent) -> p.matcher(lcContent).find();
		}
	},
	DOMAIN("domain") {
		@Override
		public MessageMatcher compile(String pattern) {
			String domain = pattern.toLowerCase(Locale.ENGLISH);

			return (msg, lcContent) -> {
				int start = 0;
				int pos;

				while ((pos = lcContent.indexOf(domain, start)) >= 0) {
					int len = lcContent.length();
					int prevPos = pos - 1;
					char prev = prevPos < 0 ? '\0' : lcContent.charAt(prevPos);
					int nextPos = pos + domain.length();
					char next = nextPos >= len ? '\0' : lcContent.charAt(nextPos);
					char afterNext = nextPos + 1 >= len ? '\0' : lcContent.charAt(nextPos + 1);

					if (!isPotentialDomainContinuation(prev) // no preceding letter/digit
							&& !isPotentialDomainContinuation(next) && (next != '.' || !isPotentialDomainContinuation(afterNext))) { // no trailing letter/digit/non-standalone-dot
						return true;
					}

					start = pos + 1;
				}

				return false;
			};
		}
	};

	public final String id;

	public static FilterType get(String id) {
		for (FilterType type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}

		throw new IllegalArgumentException("invalid type: "+id);
	}

	FilterType(String id) {
		this.id = id;
	}

	public abstract MessageMatcher compile(String pattern);

	static boolean isPotentialDomainContinuation(char c) {
		// not full ucschar coverage as per http://www.faqs.org/rfcs/rfc3987.html - but probably good enough..
		return Character.isLetterOrDigit(c) || "-_~%!$&'()*+,;=".indexOf(c) >= 0;
	}

	public interface MessageMatcher {
		boolean matches(Message message, String lowerCaseContent);
	}
}
