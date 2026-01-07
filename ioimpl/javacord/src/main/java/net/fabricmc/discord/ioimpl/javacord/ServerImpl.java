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

package net.fabricmc.discord.ioimpl.javacord;

import static net.fabricmc.discord.ioimpl.javacord.DiscordProviderImpl.join;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import org.javacord.api.entity.auditlog.AuditLogActionType;
import org.javacord.api.entity.auditlog.AuditLogEntryTarget;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;

import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Emoji;
import net.fabricmc.discord.io.Server;
import net.fabricmc.discord.io.Wrapper;

public class ServerImpl implements Server {
	private static final Wrapper<org.javacord.api.entity.server.Server, ServerImpl> WRAPPER = new Wrapper<>();

	private final org.javacord.api.entity.server.Server wrapped;
	private final DiscordImpl discord;
	private volatile MemberImpl yourself;

	ServerImpl(org.javacord.api.entity.server.Server wrapped, DiscordImpl discord) {
		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public long getId() {
		return wrapped.getId();
	}

	@Override
	public ChannelImpl getChannel(long id) {
		return ChannelImpl.wrap(wrapped.getChannelById(id).orElse(null), discord, this);
	}

	@Override
	public List<ChannelImpl> getChannels() {
		return DiscordImplUtil.wrap(wrapped.getChannels(), r -> ChannelImpl.wrap(r, discord, this));
	}

	@Override
	public List<ChannelImpl> getChannelsFiltered(Predicate<String> nameFilter) {
		List<ServerChannel> res = wrapped.getChannels();
		List<ChannelImpl> ret = new ArrayList<>(5);

		for (ServerChannel channel : res) {
			if (nameFilter.test(channel.getName())) {
				ret.add(ChannelImpl.wrap(channel, discord, ServerImpl.this));
			}
		}

		return ret;
	}

	@Override
	public MemberImpl getMember(long id) {
		return MemberImpl.wrap(wrapped.getMemberById(id).orElse(null), this);
	}

	@Override
	public MemberImpl getMember(String username, String discriminator) {
		return MemberImpl.wrap(wrapped.getMemberByNameAndDiscriminator(username, discriminator).orElse(null), this);
	}

	@Override
	public Collection<MemberImpl> getMembers() {
		return DiscordImplUtil.wrap(wrapped.getMembers(), r -> MemberImpl.wrap(r, this));
	}

	@Override
	public Collection<MemberImpl> getMembersFiltered(Predicate<String> nameFilter, boolean testServerNick, boolean testGlobalNick, boolean testUsername) {
		Collection<org.javacord.api.entity.user.User> res = wrapped.getMembers();
		List<MemberImpl> ret = new ArrayList<>(5);

		for (org.javacord.api.entity.user.User member : res) {
			String name;

			// javacord doesn't implement support for global nicks
			if (testServerNick && (name = member.getNickname(wrapped).orElse(null)) != null && nameFilter.test(name)
					|| testUsername && nameFilter.test(member.getName())) {
				ret.add(MemberImpl.wrap(member, this));
			}
		}

		return ret;
	}

	@Override
	public MemberImpl getYourself() {
		MemberImpl ret = yourself;

		if (ret == null) {
			ret = new MemberImpl(discord.getYourself(), this);
			yourself = ret;
		}

		return ret;
	}

	@Override
	public Emoji getEmoji(long id) {
		return EmojiImpl.wrap(wrapped.getCustomEmojiById(id).orElse(null), discord);
	}

	@Override
	public RoleImpl getEveryoneRole() {
		return RoleImpl.wrap(wrapped.getEveryoneRole(), discord, this);
	}

	@Override
	public RoleImpl getRole(long id) {
		return RoleImpl.wrap(wrapped.getRoleById(id).orElse(null), discord, this);
	}

	@Override
	public Ban getBan(long userId) {
		org.javacord.api.entity.server.Ban res;

		try {
			res = join(wrapped.requestBan(userId));
		} catch (NotFoundException e) {
			return null;
		} catch (DiscordException e) { // TODO: check exc
			e.printStackTrace();
			return null;
		}

		return new Ban(this, UserImpl.wrap(res.getUser(), discord), res.getReason().orElse(null));
	}

	@Override
	public void unban(long userId, String reason) {
		wrapped.unbanUser(userId, reason).join();
	}

	@Override
	public List<AuditLogEntry> getAuditLog(AuditLogType type, int count) {
		List<org.javacord.api.entity.auditlog.AuditLogEntry> res = wrapped.getAuditLog(count, AuditLogActionType.fromValue(type.id)).join().getEntries();

		return DiscordImplUtil.wrap(res, r -> new AuditLogEntry(r.getId(),
				AuditLogType.fromId(r.getType().getValue()),
				r.getUser().join().getId(), UserImpl.wrap(r.getUser().join(), discord),
				(r.getTarget().isPresent() ? r.getTarget().get().getId() : -1), resolveTarget(r),
				r.getReason().orElse(null)));
	}

	private Object resolveTarget(org.javacord.api.entity.auditlog.AuditLogEntry entry) {
		AuditLogEntryTarget target = entry.getTarget().orElse(null);
		if (target == null) return null;

		AuditLogType type = AuditLogType.fromId(entry.getType().getValue());

		return DiscordImplUtil.getAuditLogTarget(type.targetType, target.getId(), this);
	}

	@Override
	public boolean hasAllMembersInCache() {
		return wrapped.hasAllMembersInCache();
	}

	static ServerImpl wrap(org.javacord.api.entity.server.Server server, DiscordImpl discord) {
		if (server == null) return null;

		return WRAPPER.wrap(server, s -> new ServerImpl(s, discord));
	}

	org.javacord.api.entity.server.Server unwrap() {
		return wrapped;
	}
}
