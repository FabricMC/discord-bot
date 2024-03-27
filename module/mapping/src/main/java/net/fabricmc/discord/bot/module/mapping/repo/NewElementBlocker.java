/*
 * Copyright (c) 2021, 2022 FabricMC
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

package net.fabricmc.discord.bot.module.mapping.repo;

import java.io.IOException;

import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;

/**
 * Prevents adding entirely new classes, field or methods.
 *
 * <p>This takes care of mojmap and srg mapping elements that were stripped.
 */
final class NewElementBlocker extends ForwardingMappingVisitor {
	public NewElementBlocker(MappingVisitor next, MappingTree tree) {
		super(next);

		this.tree = tree;
	}

	@Override
	public boolean visitClass(String srcName) throws IOException {
		cls = tree.getClass(srcName);
		if (cls == null) return false;

		return super.visitClass(srcName);
	}

	@Override
	public boolean visitField(String srcName, String srcDesc) throws IOException {
		if (cls.getField(srcName, srcDesc) == null) return false;

		return super.visitField(srcName, srcDesc);
	}

	@Override
	public boolean visitMethod(String srcName, String srcDesc) throws IOException {
		if (cls.getMethod(srcName, srcDesc) == null) return false;

		return super.visitMethod(srcName, srcDesc);
	}

	private final MappingTree tree;
	private ClassMapping cls;
}