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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GlobalEventHolder {
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private final Map<Class<?>, List<?>> handlers = new ConcurrentHashMap<>();
	private final Map<Class<?>, TemporaryRegistrationHandler<?, ?>> tempHandlers = new ConcurrentHashMap<>();
	private Runnable updateHandler;

	private synchronized <E, H extends E> void register(Class<E> eventClass, H handler) {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		List<H> list = (List) handlers.computeIfAbsent(eventClass, ignore -> new ArrayList<>());
		list.add(handler);

		Runnable updateHandler = this.updateHandler;
		if (updateHandler != null) updateHandler.run();
	}

	public <E, H extends E> TemporaryRegistration registerTemporary(Class<E> eventClass, H handler, Runnable expireHandler, Duration timeout) {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		TemporaryRegistrationHandler<E, H> regHandler = (TemporaryRegistrationHandler) tempHandlers.computeIfAbsent(eventClass, TemporaryRegistrationHandler::new);
		TemporaryRegistration ret =  regHandler.register(handler, expireHandler, timeout);

		return ret;
	}

	public interface TemporaryRegistration {
		boolean cancel();
	}

	@SuppressWarnings("unchecked")
	public <E> List<? extends E> removeHandlers(Class<E> eventClass) {
		List<? extends E> ret = (List<? extends E>) handlers.remove(eventClass);

		return ret == null ? Collections.emptyList() : ret;
	}

	public Set<Class<?>> getHandlerTypes() {
		return handlers.keySet();
	}

	public void setUpdateHandler(Runnable handler) {
		updateHandler = handler;
		if (!handlers.isEmpty()) handler.run();
	}

	// server

	public interface ServerReadyHandler {
		void onReady(Server server);
	}

	public void registerServerReady(ServerReadyHandler handler) {
		register(ServerReadyHandler.class, handler);
	}

	public interface ServerGoneHandler {
		void onGone(Server server);
	}

	public void registerServerGone(ServerGoneHandler handler) {
		register(ServerGoneHandler.class, handler);
	}

	// channel

	public interface ChannelCreateHandler {
		void onChannelCreate(Channel message);
	}

	public void registerChannelCreate(ChannelCreateHandler handler) {
		register(ChannelCreateHandler.class, handler);
	}

	public interface ChannelDeleteHandler {
		void onChannelDelete(Channel message);
	}

	public void registerChannelDelete(ChannelDeleteHandler handler) {
		register(ChannelDeleteHandler.class, handler);
	}

	public interface ChannelPermissionChangeHandler {
		void onChannelPermissionChange(Channel channel);
	}

	public void registerChannelPermissionChange(ChannelPermissionChangeHandler handler) {
		register(ChannelPermissionChangeHandler.class, handler);
	}

	// member

	public interface MemberJoinHandler {
		void onMemberJoin(Member member);
	}

	public void registerMemberJoin(MemberJoinHandler handler) {
		register(MemberJoinHandler.class, handler);
	}

	public interface MemberLeaveHandler {
		void onMemberLeave(Member member);
	}

	public void registerMemberLeave(MemberLeaveHandler handler) {
		register(MemberLeaveHandler.class, handler);
	}

	public interface MemberNicknameChangeHandler {
		void onMemberNicknameChange(Member member, String oldNick, String newNick);
	}

	public void registerMemberNicknameChange(MemberNicknameChangeHandler handler) {
		register(MemberNicknameChangeHandler.class, handler);
	}

	public interface MemberBanHandler {
		void onMemberBan(User user, Server server);
	}

	public void registerMemberBan(MemberBanHandler handler) {
		register(MemberBanHandler.class, handler);
	}

	// message

	public interface MessageCreateHandler {
		void onMessageCreate(Message message);
	}

	public void registerMessageCreate(MessageCreateHandler handler) {
		register(MessageCreateHandler.class, handler);
	}

	public interface MessageDeleteHandler {
		void onMessageDelete(long messageId, Channel channel);
	}

	public void registerMessageDelete(MessageDeleteHandler handler) {
		register(MessageDeleteHandler.class, handler);
	}

	public interface MessageEditHandler {
		void onMessageEdit(Message message);
	}

	public void registerMessageEdit(MessageEditHandler handler) {
		register(MessageEditHandler.class, handler);
	}

	public interface MessageReactionAddHandler {
		void onMessageReactionAdd(long messageId, Emoji emoji, long userId, Channel channel);
	}

	public void registerMessageReactionAdd(MessageReactionAddHandler handler) {
		register(MessageReactionAddHandler.class, handler);
	}

	// user

	public interface UserNameChangeHandler {
		void onUserNameChange(User user, String oldName, String newName);
	}

	public void registerUserNameChange(UserNameChangeHandler handler) {
		register(UserNameChangeHandler.class, handler);
	}

	// impl

	class TemporaryRegistrationHandler<E, H extends E> {
		private final Set<H> handlers = Collections.newSetFromMap(new ConcurrentHashMap<>());

		public TemporaryRegistrationHandler(Class<E> eventClass) {
			H handler = createChainInvoker(eventClass);
			GlobalEventHolder.this.register(eventClass, handler);
		}

		TemporaryRegistration register(H handler, Runnable expireHandler, Duration timeout) {
			handlers.add(handler);

			TemporaryRegistrationImpl ret = new TemporaryRegistrationImpl(handler, expireHandler);

			Future<?> future = scheduler.schedule(ret, timeout.toMillis(), TimeUnit.MILLISECONDS);
			ret.setFuture(future);

			return ret;
		}

		private H createChainInvoker(Class<E> eventClass) {
			Method handleMethod = findHandleMethod(eventClass);
			MethodHandle targetMh;

			try {
				targetMh = MethodHandles.publicLookup().unreflect(handleMethod);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}

			targetMh = MethodHandles.dropArguments(targetMh, 1, Iterable.class); // drop Iterable that'd otherwise be supplied to targetMh, we only want the args
			MethodHandle mh = MethodHandles.iteratedLoop(null, null, targetMh)
					.asSpreader(1, Object[].class, handleMethod.getParameterCount());

			@SuppressWarnings("unchecked")
			H ret = (H) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ eventClass }, new InvocationHandler() {
				@Override
				public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
					mh.invokeExact((Iterable<?>) handlers, args);
					return null;
				}
			});

			return ret;
		}

		private static Method findHandleMethod(Class<?> eventClass) {
			for (Method method : eventClass.getMethods()) {
				if (!Modifier.isStatic(method.getModifiers())) {
					return method;
				}
			}

			throw new IllegalArgumentException("not a suitable interface");
		}

		private class TemporaryRegistrationImpl implements TemporaryRegistration, Runnable {
			private final H handler;
			private final Runnable expireHandler;
			private Future<?> future;

			TemporaryRegistrationImpl(H handler, Runnable expireHandler) {
				this.handler = handler;
				this.expireHandler = expireHandler;
			}

			void setFuture(Future<?> future) {
				this.future = future;
			}

			@Override
			public boolean cancel() {
				boolean ret = handlers.remove(handler);
				future.cancel(false);

				return ret;
			}

			@Override
			public void run() { // timeout from scheduled executor
				if (cancel()) {
					if (expireHandler != null) expireHandler.run();
				}
			}
		}
	}
}
