/*
 * Copyright (c) 2020 FabricMC
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

package net.fabricmc.discord.bot.command;

import java.net.URL;

import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.server.Server;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;

/**
 * An object which represents the context in which a command is being executed.
 */
public record CommandContext(
		DiscordBot bot,
		@Nullable Server server,
		URL messageLink,
		MessageAuthor author,
		TextChannel channel,
		String content,
		long messageId
) {}
