package net.fabricmc.discord.ioimpl.jda;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Member;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Role;

public class MemberImpl implements Member {
	private final net.dv8tion.jda.api.entities.Member wrapped;
	private final UserImpl user;
	private final ServerImpl server;

	MemberImpl(net.dv8tion.jda.api.entities.Member wrapped, UserImpl user, ServerImpl server) {
		this.wrapped = wrapped;
		this.user = user;
		this.server = server;
	}

	@Override
	public ServerImpl getServer() {
		return server;
	}

	@Override
	public UserImpl getUser() {
		return user;
	}

	@Override
	public String getDisplayName() {
		return wrapped.getEffectiveName();
	}

	@Override
	public String getNickname() {
		return wrapped.getNickname();
	}

	@Override
	public void setNickName(String newNick, String reason) {
		wrapped.modifyNickname(newNick).reason(reason).complete();
	}

	@Override
	public Instant getJoinTime() {
		return wrapped.hasTimeJoined() ? wrapped.getTimeJoined().toInstant() : null;
	}

	@Override
	public Status getStatus() {
		return Status.fromName(wrapped.getOnlineStatus().getKey());
	}

	@Override
	public String getAvatarUrl() {
		return wrapped.getEffectiveAvatarUrl();
	}

	@Override
	public List<RoleImpl> getRoles() {
		return DiscordImplUtil.wrap(wrapped.getRoles(), r -> RoleImpl.wrap(r, server.getDiscord(), server));
	}

	@Override
	public void addRole(Role role, String reason) {
		wrapped.getGuild().addRoleToMember(wrapped, ((RoleImpl) role).unwrap()).reason(reason).complete();
	}

	@Override
	public void removeRole(Role role, String reason) {
		wrapped.getGuild().removeRoleFromMember(wrapped, ((RoleImpl) role).unwrap()).reason(reason).complete();
	}

	@Override
	public Set<Permission> getPermissions() {
		return translatePermissions(wrapped.getPermissions());
	}

	@Override
	public Set<Permission> getPermissions(Channel channel) {
		if (channel instanceof GuildChannel c) {
			return translatePermissions(wrapped.getPermissions(c));
		} else {
			return channel.getPermissions(user);
		}
	}

	static Set<Permission> translatePermissions(Collection<net.dv8tion.jda.api.Permission> perms) {
		Set<Permission> ret = EnumSet.noneOf(Permission.class);

		for (net.dv8tion.jda.api.Permission jdaPerm : perms) {
			Permission perm = Permission.fromFlag(jdaPerm.getOffset());
			if (perm != null) ret.add(perm);
		}

		return ret;
	}

	@Override
	public void kick(String reason) {
		wrapped.kick().reason(reason).complete();
	}

	@Override
	public void ban(Duration messageDeleteionTimeframe, String reason) {
		wrapped.ban(Math.max(Integer.MAX_VALUE, (int) messageDeleteionTimeframe.getSeconds()), TimeUnit.SECONDS).reason(reason).complete();
	}

	static MemberImpl wrap(net.dv8tion.jda.api.entities.Member member, UserImpl user, ServerImpl server) {
		if (member == null) return null;

		if (user == null) user = UserImpl.wrap(member.getUser(), server.getDiscord());

		return new MemberImpl(member, user, server);
	}

	net.dv8tion.jda.api.entities.Member unwrap() {
		return wrapped;
	}
}
