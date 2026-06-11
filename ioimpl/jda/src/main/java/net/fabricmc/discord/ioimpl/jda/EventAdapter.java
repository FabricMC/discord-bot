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

package net.fabricmc.discord.ioimpl.jda;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class EventAdapter implements EventListener {
	private static final Logger LOGGER = LogManager.getLogger(EventAdapter.class);

	@SuppressWarnings("unchecked")
	private static final EventHandler<? extends GenericEvent>[] EMPTY_HANDLERS = new EventHandler[0];

	private final Map<Class<? extends GenericEvent>, List<EventHandler<? extends GenericEvent>>> registrations = new IdentityHashMap<>();
	private final Map<Class<? extends GenericEvent>, EventHandler<? extends GenericEvent>[]> handlers = new ConcurrentHashMap<>();

	synchronized <E extends GenericEvent> void register(Class<E> cls, EventHandler<E> handler) {
		registrations.computeIfAbsent(cls, ignore -> new ArrayList<>()).add(handler);
		handlers.clear();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onEvent(GenericEvent event) {
		Class<? extends GenericEvent> cls = event.getClass();
		EventHandler<GenericEvent>[] handlers = (EventHandler<GenericEvent>[]) this.handlers.get(cls);
		if (handlers == null) handlers = (EventHandler<GenericEvent>[]) computeHandlers(cls);
		if (handlers.length == 0) return;

		// TODO: move invocation to another thread?

		for (EventHandler<GenericEvent> handler : handlers) {
			try {
				handler.handle(event);
			} catch (Throwable t) {
				LOGGER.error("Error handling event "+event+" in "+handler, t);
			}
		}
	}

	private synchronized EventHandler<? extends GenericEvent>[] computeHandlers(Class<? extends GenericEvent> cls) {
		EventHandler<? extends GenericEvent>[] ret = handlers.get(cls);
		if (ret != null) return ret;

		List<EventHandler<? extends GenericEvent>> handlers = new ArrayList<>();
		Class<?> curCls = cls;

		do {
			List<EventHandler<? extends GenericEvent>> curHandlers = registrations.get(curCls);
			if (curHandlers != null) handlers.addAll(curHandlers);

			curCls = curCls.getSuperclass();
		} while (GenericEvent.class.isAssignableFrom(curCls));

		ret = handlers.toArray(EMPTY_HANDLERS);
		this.handlers.put(cls, ret);

		return ret;
	}

	interface EventHandler<E extends GenericEvent> {
		void handle(E event);
	}
}
