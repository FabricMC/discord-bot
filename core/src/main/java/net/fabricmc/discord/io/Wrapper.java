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

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

public final class Wrapper<O, W> {
	private final Map<O, WeakReference<W>> wrappers = new WeakHashMap<>();

	public synchronized W wrap(O orig, Function<O, W> wrapperCreator) {
		WeakReference<W> ref = wrappers.get(orig);
		W ret;

		if (ref != null && (ret = ref.get()) != null) {
			return ret;
		}

		ret = wrapperCreator.apply(orig);
		wrappers.put(orig, new WeakReference<>(ret));

		return ret;
	}
}
