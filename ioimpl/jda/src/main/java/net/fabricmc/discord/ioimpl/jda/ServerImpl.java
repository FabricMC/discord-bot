package net.fabricmc.discord.ioimpl.jda;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Emoji;
import net.fabricmc.discord.io.Server;

public class ServerImpl implements Server {
	private final Guild wrapped;
	private final DiscordImpl discord;
	private volatile MemberImpl yourself;

	ServerImpl(Guild wrapped, DiscordImpl discord) {
		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public long getId() {
		return wrapped.getIdLong();
	}

	@Override
	public ChannelImpl getChannel(long id) {
		return ChannelImpl.wrap(wrapped.getGuildChannelById(id), discord, this);
	}

	@Override
	public List<ChannelImpl> getChannels() {
		return DiscordImplUtil.wrap(wrapped.getChannels(), r -> ChannelImpl.wrap(r, discord, this));
	}

	@Override
	public List<ChannelImpl> getChannelsFiltered(Predicate<String> nameFilter) {
		List<GuildChannel> res = wrapped.getChannels();
		List<ChannelImpl> ret = new ArrayList<>(5);

		for (GuildChannel channel : res) {
			if (nameFilter.test(channel.getName())) {
				ret.add(ChannelImpl.wrap(channel, discord, ServerImpl.this));
			}
		}

		return ret;
	}

	@Override
	public MemberImpl getMember(long id) {
		return MemberImpl.wrap(wrapped.getMemberById(id), null, this);
	}

	@Override
	public MemberImpl getMember(String username, String discriminator) {
		return MemberImpl.wrap(wrapped.getMemberByTag(username, discriminator), null, this);
	}

	@Override
	public Collection<MemberImpl> getMembers() {
		return DiscordImplUtil.wrap(wrapped.getMembers(), r -> MemberImpl.wrap(r, null, this));
	}

	@Override
	public Collection<MemberImpl> getMembersFiltered(Predicate<String> nameFilter, boolean testServerNick, boolean testGlobalNick, boolean testUsername) {
		Collection<net.dv8tion.jda.api.entities.Member> res = wrapped.getMembers();
		List<MemberImpl> ret = new ArrayList<>(5);

		for (net.dv8tion.jda.api.entities.Member member : res) {
			String name;

			if (testServerNick && (name = member.getNickname()) != null && nameFilter.test(name)
					|| testGlobalNick && (name = member.getUser().getGlobalName()) != null && nameFilter.test(name)
					|| testUsername && nameFilter.test(member.getUser().getName())) {
				ret.add(MemberImpl.wrap(member, null, this));
			}
		}

		return ret;
	}

	@Override
	public MemberImpl getYourself() {
		MemberImpl ret = yourself;

		if (ret == null) {
			ret = new MemberImpl(wrapped.getSelfMember(), discord.getYourself(), this);
			yourself = ret;
		}

		return ret;
	}

	@Override
	public Emoji getEmoji(long id) {
		return EmojiImpl.wrap(wrapped.getEmojiById(id), discord);
	}

	@Override
	public RoleImpl getEveryoneRole() {
		return RoleImpl.wrap(wrapped.getPublicRole(), discord, this);
	}

	@Override
	public RoleImpl getRole(long id) {
		return RoleImpl.wrap(wrapped.getRoleById(id), discord, this);
	}

	@Override
	public Ban getBan(long userId) {
		try {
			net.dv8tion.jda.api.entities.Guild.Ban res = wrapped.retrieveBan(UserSnowflake.fromId(userId)).complete();

			return new Ban(this, UserImpl.wrap(res.getUser(), discord), res.getReason());
		} catch (ErrorResponseException e) {
			if (e.getErrorResponse() == ErrorResponse.UNKNOWN_BAN) return null;
			throw e;
		}
	}

	@Override
	public void unban(long userId, String reason) {
		wrapped.unban(UserSnowflake.fromId(userId)).reason(reason).complete();
	}

	@Override
	public List<AuditLogEntry> getAuditLog(AuditLogType type, int count) {
		List<net.dv8tion.jda.api.audit.AuditLogEntry> res = wrapped.retrieveAuditLogs().type(type != null ? ActionType.from(type.id) : null).cache(false).takeAsync(count).join();

		return DiscordImplUtil.wrap(res, r -> new AuditLogEntry(r.getIdLong(),
				AuditLogType.fromId(r.getTypeRaw()),
				r.getUserIdLong(), UserImpl.wrap(r.getUser(), discord),
				r.getTargetIdLong(), DiscordImplUtil.getAuditLogTarget(AuditLogType.fromId(r.getTypeRaw()).targetType, r.getTargetIdLong(), this),
				r.getReason()));
	}

	@Override
	public boolean hasAllMembersInCache() {
		return wrapped.isLoaded();
	}

	static ServerImpl wrap(Guild server, DiscordImpl discord) {
		if (server == null) return null;

		return new ServerImpl(server, discord);
	}

	Guild unwrap() {
		return wrapped;
	}
}
