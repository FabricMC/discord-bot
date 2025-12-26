package net.fabricmc.discord.io;

import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import net.fabricmc.discord.io.Server.AuditLogTargetType;

public class DiscordImplUtil {
	public static <I, O> Collection<O> wrap(Collection<I> items, Function<I, O> wrapper) {
		return new AbstractCollection<O>() {
			Iterator<I> resIt = items.iterator();

			@Override
			public Iterator<O> iterator() {
				return new Iterator<O>() {
					@Override
					public boolean hasNext() {
						return resIt.hasNext();
					}

					@Override
					public O next() {
						return wrapper.apply(resIt.next());
					}
				};
			}

			@Override
			public int size() {
				return items.size();
			}
		};
	}

	public static <I, O> List<O> wrap(List<I> items, Function<I, O> wrapper) {
		return new AbstractList<O>() {
			@Override
			public O get(int index) {
				return wrapper.apply(items.get(index));
			}

			@Override
			public int size() {
				return items.size();
			}
		};
	}

	public static Object getAuditLogTarget(AuditLogTargetType type, long id, Server server) {
		return switch (type) {
		case CHANNEL -> server.getChannel(id);
		case GUILD -> server;
		case MEMBER -> server.getMember(id);
		case ROLE -> server.getRole(id);
		case THREAD -> server.getChannel(id);
		default -> null;
		};
	}
}
