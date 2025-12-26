package net.fabricmc.discord.ioimpl.jda;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.EmbedType;
import net.dv8tion.jda.api.entities.MessageEmbed.AuthorInfo;
import net.dv8tion.jda.api.entities.MessageEmbed.Footer;
import net.dv8tion.jda.api.entities.MessageEmbed.ImageInfo;
import net.dv8tion.jda.api.entities.MessageEmbed.Thumbnail;

import net.fabricmc.discord.io.MessageEmbed;

public class MessageEmbedImpl implements MessageEmbed {
	private final net.dv8tion.jda.api.entities.MessageEmbed wrapped;

	MessageEmbedImpl(net.dv8tion.jda.api.entities.MessageEmbed wrapped) {
		this.wrapped = wrapped;
	}

	@Override
	public Type getType() {
		EmbedType req = wrapped.getType();

		for (Type type : Type.values()) {
			EmbedType cmp = EmbedType.fromKey(type.name);
			if (cmp != EmbedType.UNKNOWN && cmp == req) return type;
		}

		return Type.OTHER;
	}

	@Override
	public String getTitle() {
		return wrapped.getTitle();
	}

	@Override
	public String getTitleUrl() {
		return wrapped.getUrl();
	}

	@Override
	public String getDescription() {
		return wrapped.getDescription();
	}

	@Override
	public String getFooterText() {
		Footer footer = wrapped.getFooter();

		return footer != null ? footer.getText() : null;
	}

	@Override
	public String getFooterIconUrl() {
		Footer footer = wrapped.getFooter();

		return footer != null ? footer.getIconUrl() : null;
	}

	@Override
	public List<Field> getFields() {
		List<net.dv8tion.jda.api.entities.MessageEmbed.Field> res = wrapped.getFields();
		List<Field> ret = new ArrayList<>(res.size());

		for (net.dv8tion.jda.api.entities.MessageEmbed.Field field : res) {
			ret.add(new Field(field.getName(), field.getValue(), field.isInline()));
		}

		return ret;
	}

	@Override
	public String getImageUrl() {
		ImageInfo res = wrapped.getImage();

		return res != null ? res.getUrl() : null;
	}

	@Override
	public String getThumbnailUrl() {
		Thumbnail thumbnail = wrapped.getThumbnail();

		return thumbnail != null ? thumbnail.getUrl() : null;
	}

	@Override
	public Color getColor() {
		return wrapped.getColor();
	}

	@Override
	public String getAuthorName() {
		AuthorInfo res = wrapped.getAuthor();

		return res != null ? res.getName() : null;
	}

	@Override
	public String getAuthorUrl() {
		AuthorInfo res = wrapped.getAuthor();

		return res != null ? res.getUrl() : null;
	}

	@Override
	public String getAuthorIconUrl() {
		AuthorInfo res = wrapped.getAuthor();

		return res != null ? res.getIconUrl() : null;
	}

	@Override
	public Instant getTime() {
		OffsetDateTime res = wrapped.getTimestamp();

		return res != null ? res.toInstant() : null;
	}

	static MessageEmbedImpl wrap(net.dv8tion.jda.api.entities.MessageEmbed embed) {
		if (embed == null) return null;

		return new MessageEmbedImpl(embed);
	}

	static EmbedBuilder toBuilder(MessageEmbed embed) {
		EmbedBuilder ret = new EmbedBuilder()
				.setTitle(embed.getTitle())
				.setUrl(embed.getTitleUrl())
				.setDescription(embed.getDescription())
				.setFooter(embed.getFooterText(), embed.getFooterIconUrl())
				.setImage(embed.getImageUrl())
				.setThumbnail(embed.getThumbnailUrl())
				.setColor(embed.getColor())
				.setAuthor(embed.getAuthorName(), embed.getAuthorUrl(), embed.getAuthorIconUrl())
				.setTimestamp(embed.getTime());

		for (Field field : embed.getFields()) {
			ret.addField(field.name(), field.value(), field.inline());
		}

		return ret;
	}
}
