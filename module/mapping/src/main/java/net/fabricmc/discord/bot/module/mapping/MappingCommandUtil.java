/*
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.discord.bot.module.mapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.module.mcversion.McVersionRepo;

final class MappingCommandUtil {
	public static String getMcVersion(CommandContext context, Map<String, String> arguments) throws CommandException {
		String ret = arguments.get("mcVersion");
		if (ret == null) ret = arguments.get("unnamed_1");

		ret = McVersionRepo.get(context.bot()).resolve(context, ret);
		if (ret == null) throw new CommandException("invalid version or latest version data is unavailable");

		return ret;
	}

	public static MappingData getMappingData(MappingRepository repo, String mcVersion) throws CommandException {
		MappingData data = repo.getMappingData(mcVersion);
		if (data == null) throw new CommandException("Invalid/unavailable MC version");

		return data;
	}

	public static List<String> getNamespaces(CommandContext context, Map<String, String> arguments, boolean forQuery) throws CommandException {
		String val = arguments.get(forQuery ? "queryNs" : "displayNs");
		if (val == null) val = arguments.get("ns");

		return getNamespaces(context, val, forQuery, true);
	}

	public static List<String> getNamespaces(CommandContext context, String val, boolean forQuery, boolean checkPublic) throws CommandException {
		List<String> ret;

		if (val != null) {
			ret = Arrays.asList(val.split(","));
			int modifierCount = 0;

			for (String ns : ret) {
				if (ns.startsWith("+") || ns.startsWith("-")) {
					modifierCount++;
				}
			}

			if (modifierCount > 0) { // modifiers present
				List<String> modifiers = ret;

				// initialize to configured if only +-, otherwise to non-+-
				if (modifierCount == ret.size()) { // exclusively modifiers
					ret = new ArrayList<>(getConfiguredNamespaces(context, forQuery));
				} else { // normal entries + modifiers
					ret = new ArrayList<>(modifiers.size());

					for (String mod : modifiers) {
						if (!mod.startsWith("+") && !mod.startsWith("-")) {
							ret.add(mod);
						}
					}
				}

				// process additions
				for (String mod : modifiers) {
					if (mod.startsWith("+")) {
						ret.add(mod.substring(1));
					}
				}

				// unify
				ret = new ArrayList<>(new LinkedHashSet<>(ret));

				// process removals
				for (String mod : modifiers) {
					if (mod.startsWith("-")) {
						ret.remove(mod.substring(1));
					}
				}
			}
		} else {
			ret = getConfiguredNamespaces(context, forQuery);
		}

		// trim to allowed only
		boolean removedPrivateNs = false;

		if (checkPublic && !context.isPrivateMessage()) {
			List<String> privateNs = getPrivateNamespaces(context, ret);

			if (!privateNs.isEmpty()) {
				int prevSize = ret.size();
				ret = new ArrayList<>(ret);
				ret.removeAll(privateNs);
				removedPrivateNs = ret.size() < prevSize;
			}
		}

		// trim to supported only
		if (!MappingModule.supportedNamespaces.containsAll(ret)) {
			ret = new ArrayList<>(ret);
			ret.retainAll(MappingModule.supportedNamespaces);
		}

		if (ret.isEmpty()) throw new CommandException(removedPrivateNs ? "all selected namespaces are DM only" : "no valid namespaces");

		return ret;
	}

	public static List<String> getConfiguredNamespaces(CommandContext context, boolean forQuery) {
		return Command.getUserConfig(context,
				(forQuery ? MappingModule.QUERY_NAMESPACES : MappingModule.DISPLAY_NAMESPACES),
				Command.getConfig(context, MappingModule.DEFAULT_NAMESPACES));
	}

	public static List<String> getPrivateNamespaces(CommandContext context, List<String> namespaces) {
		List<String> publicNs = Command.getConfig(context, MappingModule.PUBLIC_NAMESPACES);
		List<String> ret = null;

		for (String ns : namespaces) {
			if (!publicNs.contains(ns)) {
				if (ret == null) ret = new ArrayList<>();
				ret.add(ns);
			}
		}

		return ret != null ? ret : Collections.emptyList();
	}
}
