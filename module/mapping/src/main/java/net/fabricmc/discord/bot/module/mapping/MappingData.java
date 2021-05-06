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

import static net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.MIN_NAMESPACE_ID;
import static net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.NULL_NAMESPACE_ID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.ClassMapping;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.FieldMapping;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree.MethodMapping;

final class MappingData {
	private static final String intermediaryClassPrefix = "class_";
	private static final String intermediaryFullClassPrefix = "net/minecraft/"+intermediaryClassPrefix;
	private static final String intermediaryFieldPrefix = "field_";
	private static final String intermediaryMethodPrefix = "method_";

	public final String mcVersion;
	public final String intermediaryMavenId;
	public final String yarnMavenId;
	private final MappingTree mappingTree;
	private final int maxNsId;
	private final int intermediaryNs;
	private final Map<Integer, ClassMapping> classByIntermediaryId;
	private final Map<Integer, FieldMapping> fieldByIntermediaryId = new HashMap<>(5000);
	private final Map<Integer, MethodMapping> methodByIntermediaryId = new HashMap<>(5000);
	private final Map<String, List<ClassMapping>>[] classByName;
	private final Map<String, List<FieldMapping>>[] fieldByName;
	private final Map<String, List<MethodMapping>>[] methodByName;

	@SuppressWarnings("unchecked")
	public MappingData(String mcVersion, String intermediaryMavenId, String yarnMavenId, MappingTree mappingTree) {
		this.mcVersion = mcVersion;
		this.intermediaryMavenId = intermediaryMavenId;
		this.yarnMavenId = yarnMavenId;
		this.mappingTree = mappingTree;

		this.classByIntermediaryId = new HashMap<>(mappingTree.getClasses().size());
		intermediaryNs = mappingTree.getNamespaceId("intermediary");

		maxNsId = mappingTree.getMaxNamespaceId();
		int nsCount = maxNsId - MIN_NAMESPACE_ID;
		classByName = new Map[nsCount];
		fieldByName = new Map[nsCount];
		methodByName = new Map[nsCount];

		initIndices();
	}

	private void initIndices() {
		for (int i = 0; i < classByName.length; i++) {
			if (i == intermediaryNs - MIN_NAMESPACE_ID) {
				classByName[i] = Collections.emptyMap();
				fieldByName[i] = Collections.emptyMap();
				methodByName[i] = Collections.emptyMap();
			} else {
				classByName[i] = new HashMap<>(mappingTree.getClasses().size());
				fieldByName[i] = new HashMap<>(5000);
				methodByName[i] = new HashMap<>(5000);
			}
		}

		for (ClassMapping cls : mappingTree.getClasses()) {
			if (intermediaryNs != NULL_NAMESPACE_ID) {
				String imName = cls.getDstName(intermediaryNs);

				if (imName != null) {
					int idStart = detectIntermediaryIdStart(imName, false);

					if (idStart >= 0) {
						try {
							classByIntermediaryId.putIfAbsent(Integer.parseUnsignedInt(imName, idStart, imName.length(), 10), cls);
						} catch (NumberFormatException e) { }
					}
				}
			}

			for (int ns = MIN_NAMESPACE_ID; ns < maxNsId; ns++) {
				if (ns == intermediaryNs) continue;

				String name = cls.getName(ns);
				if (name == null) continue;

				int pkgEnd = name.lastIndexOf('/');
				if (pkgEnd >= 0) name = name.substring(pkgEnd + 1);

				classByName[ns - MIN_NAMESPACE_ID].computeIfAbsent(name, ignore -> new ArrayList<>()).add(cls);
			}

			for (FieldMapping field : cls.getFields()) {
				if (intermediaryNs != NULL_NAMESPACE_ID) {
					String imName = field.getDstName(intermediaryNs);

					if (imName != null && imName.startsWith(intermediaryFieldPrefix)) {
						try {
							fieldByIntermediaryId.putIfAbsent(Integer.parseUnsignedInt(imName, intermediaryFieldPrefix.length(), imName.length(), 10), field);
						} catch (NumberFormatException e) { }
					}
				}

				for (int ns = MIN_NAMESPACE_ID; ns < maxNsId; ns++) {
					if (ns == intermediaryNs) continue;

					String name = field.getName(ns);
					if (name == null) continue;

					fieldByName[ns - MIN_NAMESPACE_ID].computeIfAbsent(name, ignore -> new ArrayList<>()).add(field);
				}
			}

			for (MethodMapping method : cls.getMethods()) {
				if (intermediaryNs != NULL_NAMESPACE_ID) {
					String imName = method.getDstName(intermediaryNs);

					if (imName != null && imName.startsWith(intermediaryMethodPrefix)) {
						try {
							methodByIntermediaryId.putIfAbsent(Integer.parseUnsignedInt(imName, intermediaryMethodPrefix.length(), imName.length(), 10), method);
						} catch (NumberFormatException e) { }
					}
				}

				for (int ns = MIN_NAMESPACE_ID; ns < maxNsId; ns++) {
					if (ns == intermediaryNs) continue;

					String name = method.getName(ns);
					if (name == null) continue;

					methodByName[ns - MIN_NAMESPACE_ID].computeIfAbsent(name, ignore -> new ArrayList<>()).add(method);
				}
			}
		}
	}

	private static int detectIntermediaryIdStart(String name, boolean allowInner) {
		int outerEnd = name.lastIndexOf('$');

		if ((outerEnd >= 0 || allowInner) && name.startsWith(intermediaryClassPrefix, outerEnd + 1)) { // nested intermediary: bla$class_123 or just class_123 with allowInner=true
			return outerEnd + 1 + intermediaryClassPrefix.length();
		} else if (outerEnd < 0 && name.startsWith(intermediaryFullClassPrefix)) { // regular intermediary: net/minecraft/class_123
			return  intermediaryFullClassPrefix.length();
		} else {
			return -1;
		}
	}

	public Set<ClassMapping> findClasses(String name) {
		Set<ClassMapping> ret = new HashSet<>();
		name = parseClassName(name);

		for (int ns = MIN_NAMESPACE_ID; ns < maxNsId; ns++) {
			findClasses0(name, ns, ret);
		}

		return ret;
	}

	public Set<ClassMapping> findClasses(String name, String namespace) {
		int ns = mappingTree.getNamespaceId(namespace);
		if (ns == NULL_NAMESPACE_ID) return Collections.emptySet();

		Set<ClassMapping> ret = new HashSet<>();
		findClasses0(parseClassName(name), ns, ret);

		return ret;
	}

	private void findClasses0(String name, int namespace, Collection<ClassMapping> out) {
		if (hasWildcard(name)) { // wildcard present
			Pattern search = compileSearch(name);

			if (name.indexOf('/') > 0) { // package present too
				for (ClassMapping cls : mappingTree.getClasses()) {
					if (search.matcher(cls.getName(namespace)).matches()) {
						out.add(cls);
					}
				}
			} else { // just a wildcard class name
				for (Entry<String, List<ClassMapping>> entry : classByName[namespace - MIN_NAMESPACE_ID].entrySet()) {
					if (search.matcher(entry.getKey()).matches()) {
						out.addAll(entry.getValue());
					}
				}
			}
		} else if (name.indexOf('/') >= 0) { // package present
			ClassMapping cls = mappingTree.getClass(name, namespace);
			if (cls != null) out.add(cls);
		} else if (namespace == intermediaryNs) {
			int idStart = detectIntermediaryIdStart(name, true);
			if (idStart < 0) idStart = 0; // allow just the intermediary number

			try {
				int id = Integer.parseUnsignedInt(name, idStart, name.length(), 10);
				ClassMapping cls = classByIntermediaryId.get(id);
				if (cls != null) out.add(cls);
			} catch (NumberFormatException e) { }
		} else {
			List<ClassMapping> res = classByName[namespace - MIN_NAMESPACE_ID].get(name);
			if (res != null) out.addAll(res);
		}
	}

	public static boolean hasWildcard(String name) {
		return name != null && (name.indexOf('?') >= 0 || name.indexOf('*') >= 0);
	}

	private static Pattern compileSearch(String search) {
		int end = search.length();
		StringBuilder filter = new StringBuilder(end);
		int last = -1;

		for (int i = 0; i < end; i++) {
			char c = search.charAt(i);

			switch (c) {
			case '*':
				if (last >= 0) {
					filter.append(Pattern.quote(search.substring(last, i)));
					last = -1;
				}

				// drain any immediately subsequent asterisks
				while (i + 1 < end && search.charAt(i + 1) == '*') i++;
				filter.append(".*");
				break;

			case '?':
				if (last >= 0) {
					filter.append(Pattern.quote(search.substring(last, i)));
					last = -1;
				}

				filter.append('.');
				break;

			case '\\': // allow escaping with \
				if (last >= 0) {
					filter.append(Pattern.quote(search.substring(last, i)));
				}

				last = ++i;
				break;

			default:
				if (last < 0) last = i;
				break;
			}
		}

		// make sure not to leave off anything from the end
		if (last >= 0) filter.append(Pattern.quote(search.substring(last, end)));

		return Pattern.compile(filter.toString());
	}

	public Set<FieldMapping> findFields(String name) {
		Set<FieldMapping> ret = new HashSet<>();
		MemberRef ref = parseMemberName(name);

		for (int ns = MIN_NAMESPACE_ID; ns < maxNsId; ns++) {
			findFields0(ref, ns, ret);
		}

		return ret;
	}

	public Set<FieldMapping> findFields(String name, String namespace) {
		int ns = mappingTree.getNamespaceId(namespace);
		if (ns == NULL_NAMESPACE_ID) return Collections.emptySet();

		Set<FieldMapping> ret = new HashSet<>();
		findFields0(parseMemberName(name), ns, ret);

		return ret;
	}

	private void findFields0(MemberRef ref, int namespace, Collection<FieldMapping> out) {
		if (ref.owner() != null) { // owner/package present
			Set<ClassMapping> owners = new HashSet<>();
			findClasses0(ref.owner(), namespace, owners);

			if (!owners.isEmpty()) {
				if (hasWildcard(ref.name())) {
					Pattern search = compileSearch(ref.name());

					for (ClassMapping cls : owners) {
						for (FieldMapping field : cls.getFields()) {
							if (search.matcher(field.getName(namespace)).matches()) {
								out.add(field);
							}
						}
					}
				} else {
					for (ClassMapping cls : owners) {
						for (FieldMapping field : cls.getFields()) {
							if (ref.name().equals(field.getName(namespace))) {
								out.add(field);
							}
						}
					}
				}

				owners.clear();
			}
		} else if (hasWildcard(ref.name())) {
			Pattern search = compileSearch(ref.name());

			for (Entry<String, List<FieldMapping>> entry : fieldByName[namespace - MIN_NAMESPACE_ID].entrySet()) {
				if (search.matcher(entry.getKey()).matches()) {
					out.addAll(entry.getValue());
				}
			}
		} else if (namespace == intermediaryNs) {
			String name = ref.name();

			if (name.startsWith(intermediaryFieldPrefix)) {
				name = name.substring(intermediaryFieldPrefix.length());
			}

			try {
				int id = Integer.parseUnsignedInt(name);
				FieldMapping field = fieldByIntermediaryId.get(id);
				if (field != null) out.add(field);
			} catch (NumberFormatException e) { }
		} else {
			List<FieldMapping> res = fieldByName[namespace - MIN_NAMESPACE_ID].get(ref.name());
			if (res != null) out.addAll(res);
		}
	}

	public Set<MethodMapping> findMethods(String name) {
		Set<MethodMapping> ret = new HashSet<>();
		MemberRef ref = parseMemberName(name);

		for (int ns = MIN_NAMESPACE_ID; ns < maxNsId; ns++) {
			findMethods0(ref, ns, ret);
		}

		return ret;
	}

	public Set<MethodMapping> findMethods(String name, String namespace) {
		int ns = mappingTree.getNamespaceId(namespace);
		if (ns == NULL_NAMESPACE_ID) return Collections.emptySet();

		Set<MethodMapping> ret = new HashSet<>();
		findMethods0(parseMemberName(name), ns, ret);

		return ret;
	}

	private void findMethods0(MemberRef ref, int namespace, Collection<MethodMapping> out) {
		if (ref.owner() != null) { // owner/package present
			Set<ClassMapping> owners = new HashSet<>();
			findClasses0(ref.owner(), namespace, owners);

			if (!owners.isEmpty()) {
				if (hasWildcard(ref.name())) {
					Pattern search = compileSearch(ref.name());

					for (ClassMapping cls : owners) {
						for (MethodMapping method : cls.getMethods()) {
							if (search.matcher(method.getName(namespace)).matches()) {
								out.add(method);
							}
						}
					}
				} else {
					for (ClassMapping cls : owners) {
						for (MethodMapping method : cls.getMethods()) {
							if (ref.name().equals(method.getName(namespace))) {
								out.add(method);
							}
						}
					}

					// try to resolve constructor in Java name form ([pkg/]ClsName/ClsName) as <init>
					if (ref.owner().endsWith(ref.name())
							&& (ref.owner().length() == ref.name().length() || ref.owner().charAt(ref.owner().length() - ref.name().length() - 1) == '/')) {
						for (ClassMapping cls : owners) {
							for (MethodMapping method : cls.getMethods()) {
								if ("<init>".equals(method.getName(namespace))) {
									out.add(method);
								}
							}
						}
					}
				}

				owners.clear();
			}
		} else if (hasWildcard(ref.name())) {
			Pattern search = compileSearch(ref.name());

			for (Entry<String, List<MethodMapping>> entry : methodByName[namespace - MIN_NAMESPACE_ID].entrySet()) {
				if (search.matcher(entry.getKey()).matches()) {
					out.addAll(entry.getValue());
				}
			}
		} else if (namespace == intermediaryNs) {
			String name = ref.name();

			if (name.startsWith(intermediaryMethodPrefix)) {
				name = name.substring(intermediaryMethodPrefix.length());
			}

			try {
				int id = Integer.parseUnsignedInt(name);
				MethodMapping method = methodByIntermediaryId.get(id);
				if (method != null) out.add(method);
			} catch (NumberFormatException e) { }
		} else {
			List<MethodMapping> res = methodByName[namespace - MIN_NAMESPACE_ID].get(ref.name());
			if (res != null) out.addAll(res);
		}
	}

	private static String parseClassName(String name) {
		name = name.replace('.', '/');

		if (name.startsWith("L") && name.endsWith(";")) { // mixin-like owner (descriptor instead of name): Lbla;
			name = name.substring(1, name.length() - 1);
		}

		return name;
	}

	private static MemberRef parseMemberName(String name) {
		name = name.replace('.', '/').replace('#', '/');

		// adjacent method desc: name(Lbla;)V
		int nameEnd = name.lastIndexOf('(');
		int descStart = nameEnd;

		// matcher-style field desc: name;;Lbla;
		if (nameEnd < 0) {
			nameEnd = name.lastIndexOf(";;");
			if (nameEnd >= 0) descStart = nameEnd + 2;
		}

		// mixin-style field desc: name:Lbla;
		if (nameEnd < 0) {
			nameEnd = name.lastIndexOf(":");
			if (nameEnd >= 0) descStart = nameEnd + 1;
		}

		// no desc
		if (nameEnd < 0) nameEnd = name.length();

		int ownerStart = 0;
		int ownerEnd = name.lastIndexOf('/', nameEnd - 1);

		if (name.startsWith("L")) { // potentially mixin-like owner (descriptor instead of name): Lbla;getX
			int altOwnerEnd = name.lastIndexOf(';', nameEnd - 1);

			if (altOwnerEnd > 0) {
				ownerStart = 1;
				ownerEnd = altOwnerEnd;
			}
		}

		String owner, mname;

		if (ownerEnd < 0) {
			owner = null;
			mname = name.substring(0, nameEnd);
		} else {
			owner = name.substring(ownerStart, ownerEnd);
			mname = name.substring(ownerEnd + 1, nameEnd);
		}

		String desc = descStart >= 0 ? name.substring(descStart) : null; // TODO: translate java signature to asm desc as needed, make use of desc

		return new MemberRef(owner, mname, desc);
	}

	private record MemberRef(String owner, String name, String desc) { }
}