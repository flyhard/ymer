/*
 * Copyright 2015 Avanza Bank AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.avanza.ymer;

import java.util.Arrays;
import java.util.EnumSet;

import com.avanza.ymer.MirroredDocument.Flag;
/**
 * Holds information about one mirrored space object type.
 *
 * @author Elias Lindholm, Joakim Sahlstrom
 *
 */
public final class MirroredDocumentBuilder<T> {

	final Class<T> mirroredType;
	String collectionName;
	EnumSet<MirroredDocument.Flag> flags = EnumSet.noneOf(MirroredDocument.Flag.class);
	DocumentPatch[] patches;

	public MirroredDocumentBuilder(Class<T> mirroredType) {
		this.mirroredType = mirroredType;
	}

    public MirroredDocumentBuilder<T> collectionName(String collectionName) {
        this.collectionName = collectionName;
		return this;
    }

    public MirroredDocumentBuilder<T> flags(MirroredDocument.Flag first, MirroredDocument.Flag... rest) {
    	this.flags = EnumSet.of(first, rest);
    	return this;
	}
    
    public MirroredDocumentBuilder<T> documentPatches(DocumentPatch... patches) {
    	this.patches = patches;
    	return this;
	}
    
    public MirroredDocument<T> build() {
    	return new MirroredDocument<T>(this);
    }

	DocumentPatchChain<T> createPatchChain() {
		return new DocumentPatchChain<T>(mirroredType, Arrays.asList(patches));
	}

	boolean excludeFromInitialLoad() {
		return flags.contains(Flag.EXCLUDE_FROM_INITIAL_LOAD);
	}

	boolean writeBackPatchedDocuments() {
		return !flags.contains(Flag.DO_NOT_WRITE_BACK_PATCHED_DOCUMENTS);
	}

	boolean loadDocumentsRouted() {
		return flags.contains(Flag.LOAD_DOCUMENTS_ROUTED);
	}

	boolean keepPersistent() {
		return flags.contains(Flag.KEEP_PERSISTENT);
	}

	String collectionName() {
		return this.collectionName;
	}

}