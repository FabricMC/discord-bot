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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class Wrapper<O, W extends Entity> {
	private static final Logger LOGGER = LogManager.getLogger("Wrapper");

	private static final ReferenceQueue<Ref<?>> REF_QUEUE = new ReferenceQueue<>();

	private final ToLongFunction<O> idGetter;
	private final Long2ObjectMap<RefEntry<O, W>> idToRefs = new Long2ObjectOpenHashMap<>();

	public Wrapper(ToLongFunction<O> idGetter) {
		this.idGetter = idGetter;
	}

	public synchronized W wrap(O orig, Function<O, W> wrapperCreator) {
		long id = idGetter.applyAsLong(orig);
		RefEntry<O, W> entry = idToRefs.get(id);
		W ret;

		if (entry != null) {
			if (entry.obj == null
					|| entry.obj.get() != orig) { // orig missing/changed -> update
				entry.obj = createRef(orig, id);
			}

			if (entry.wrapper == null
					|| (ret = entry.wrapper.get()) == null) { // wrapper missing -> create
				ret = wrapperCreator.apply(orig);
				entry.wrapper = createRef(ret, id);
			}
		} else {
			ret = wrapperCreator.apply(orig);
			idToRefs.put(id, new RefEntry<>(createRef(orig, id), createRef(ret, id)));
		}

		return ret;
	}

	public synchronized @Nullable O unwrap(W wrapped) {
		long id = wrapped.getId();
		RefEntry<O, W> entry = idToRefs.get(id);

		if (entry != null && entry.obj != null) {
			return entry.obj.get();
		} else {
			return null;
		}
	}

	synchronized void cleanup(Ref<?> ref) {
		long id = ref.id;
		RefEntry<O, W> entry = idToRefs.get(id);
		if (entry == null) return;

		if (entry.obj == ref) {
			entry.obj = null;
		} else if (entry.wrapper == ref) {
			entry.wrapper = null;
		} else { // outdated?
			return;
		}

		if (entry.obj == null && entry.wrapper == null) {
			idToRefs.remove(id);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> Ref<T> createRef(T referent, long id) {
		return new Ref<T>(this, referent, id, (ReferenceQueue<? super T>) REF_QUEUE);
	}

	private static class Ref<T> extends WeakReference<T> {
		private final Wrapper<?, ?> wrapper;
		private final long id;
		private final String toStr;

		Ref(Wrapper<?, ?> wrapper, T referent, long id, ReferenceQueue<? super T> q) {
			super(referent, q);

			this.wrapper = wrapper;
			this.id = id;
			this.toStr = referent.toString();
		}

		@Override
		public String toString() {
			return toStr;
		}
	}

	private static class RefEntry<O, W> {
		Ref<O> obj;
		Ref<W> wrapper;

		RefEntry(Ref<O> obj, Ref<W> wrapper) {
			this.obj = obj;
			this.wrapper = wrapper;
		}
	}

	private static class RefCleanupThread extends Thread {
		RefCleanupThread() {
			super("wrapper ref cleanup thread");

			setDaemon(true);
			start();
		}

		@Override
		public void run() {
			for (;;) {
				try {
					Ref<?> ref = (Ref<?>) REF_QUEUE.remove();
					ref.wrapper.cleanup(ref);
				} catch (InterruptedException e) {
					break;
				} catch (Throwable t) {
					LOGGER.error("Error processing ref queue.", t);
					return;
				}
			}
		}
	}

	static {
		new RefCleanupThread();
	}
}
