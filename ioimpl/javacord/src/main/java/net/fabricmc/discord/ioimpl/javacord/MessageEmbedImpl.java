package net.fabricmc.discord.ioimpl.javacord;

import static net.fabricmc.discord.ioimpl.javacord.DiscordProviderImpl.urlToString;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedAuthor;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.message.embed.EmbedField;
import org.javacord.api.entity.message.embed.EmbedFooter;
import org.javacord.api.entity.message.embed.EmbedImage;
import org.javacord.api.entity.message.embed.EmbedThumbnail;

import net.fabricmc.discord.io.MessageEmbed;

public class MessageEmbedImpl implements MessageEmbed {
	private final Embed wrapped;

	MessageEmbedImpl(Embed wrapped) {
		this.wrapped = wrapped;
	}

	@Override
	public Type getType() {
		return Type.fromName(wrapped.getType());
	}

	@Override
	public String getTitle() {
		return wrapped.getTitle().orElse(null);
	}

	@Override
	public String getTitleUrl() {
		return urlToString(wrapped.getUrl());
	}

	@Override
	public String getDescription() {
		return wrapped.getDescription().orElse(null);
	}

	@Override
	public String getFooterText() {
		EmbedFooter footer = wrapped.getFooter().orElse(null);

		return footer != null ? footer.getText().orElse(null) : null;
	}

	@Override
	public String getFooterIconUrl() {
		EmbedFooter res = wrapped.getFooter().orElse(null);
		if (res == null) return null;

		return res != null ? urlToString(res.getIconUrl()) : null;
	}

	@Override
	public List<Field> getFields() {
		List<EmbedField> res = wrapped.getFields();
		List<Field> ret = new ArrayList<>(res.size());

		for (EmbedField field : res) {
			ret.add(new Field(field.getName(), field.getValue(), field.isInline()));
		}

		return ret;
	}

	@Override
	public String getImageUrl() {
		EmbedImage res = wrapped.getImage().orElse(null);

		return res != null ? urlToString(res.getUrl()) : null;
	}

	@Override
	public String getThumbnailUrl() {
		EmbedThumbnail thumbnail = wrapped.getThumbnail().orElse(null);

		return thumbnail != null ? urlToString(thumbnail.getUrl()) : null;
	}

	@Override
	public Color getColor() {
		return wrapped.getColor().orElse(null);
	}

	@Override
	public String getAuthorName() {
		EmbedAuthor res = wrapped.getAuthor().orElse(null);

		return res != null ? res.getName() : null;
	}

	@Override
	public String getAuthorUrl() {
		EmbedAuthor res = wrapped.getAuthor().orElse(null);

		return res != null ? urlToString(res.getUrl()) : null;
	}

	@Override
	public String getAuthorIconUrl() {
		EmbedAuthor res = wrapped.getAuthor().orElse(null);

		return res != null ? urlToString(res.getIconUrl()) : null;
	}

	@Override
	public Instant getTime() {
		return wrapped.getTimestamp().orElse(null);
	}

	static MessageEmbedImpl wrap(Embed embed) {
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
