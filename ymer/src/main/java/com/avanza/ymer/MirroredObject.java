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

import com.gigaspaces.annotation.pojo.SpaceId;
import com.gigaspaces.annotation.pojo.SpaceRouting;
import com.mongodb.BasicDBObject;
import com.mongodb.ReadPreference;

import java.lang.reflect.Method;

import org.bson.Document;

/**
 * Holds information about one mirrored space object type.
 *
 * @author Elias Lindholm, Joakim Sahlstrom
 *
 */
final class MirroredObject<T> {

	public static final String DOCUMENT_FORMAT_VERSION_PROPERTY = "_formatVersion";
	public static final String DOCUMENT_ROUTING_KEY = "_routingKey";
	private final DocumentPatchChain<T> patchChain;
	private final RoutingKeyExtractor routingKeyExtractor;
	private final boolean excludeFromInitialLoad;
	private final boolean writeBackPatchedDocuments;
	private final boolean loadDocumentsRouted;
	private final boolean keepPersistent;
    private final String collectionName;
	private final TemplateFactory customInitialLoadTemplateFactory;
	private final ReadPreference readPreference;

	public MirroredObject(MirroredObjectDefinition<T> definition, MirroredObjectDefinitionsOverride override) {
		this.patchChain = definition.createPatchChain();
		this.routingKeyExtractor = findRoutingKeyMethod(patchChain.getMirroredType());
		this.excludeFromInitialLoad = override.excludeFromInitialLoad(definition);
        this.writeBackPatchedDocuments = override.writeBackPatchedDocuments(definition);
        this.loadDocumentsRouted = override.loadDocumentsRouted(definition);
        this.keepPersistent = definition.keepPersistent();
        this.collectionName = definition.collectionName();
        this.customInitialLoadTemplateFactory = definition.customInitialLoadTemplateFactory();
        this.readPreference = definition.getReadPreference();
	}

	private RoutingKeyExtractor findRoutingKeyMethod(Class<T> mirroredType) {
		for (Method m : mirroredType.getMethods()) {
			if (m.isAnnotationPresent(SpaceRouting.class) && !m.isAnnotationPresent(SpaceId.class)) {
				return new RoutingKeyExtractor.InstanceMethod(m);
			}
		}
		for (Method m : mirroredType.getMethods()) {
			if (m.isAnnotationPresent(SpaceId.class)) {
				if (RoutingKeyExtractor.GsAutoGenerated.isApplicable(m)) {
					return new RoutingKeyExtractor.GsAutoGenerated(m);
				} else {
					return new RoutingKeyExtractor.InstanceMethod(m);
				}
			}
		}
		throw new IllegalArgumentException("Cannot find @SpaceRouting or @SpaceId method for: " + mirroredType.getName());
	}

	Class<T> getMirroredType() {
		return patchChain.getMirroredType();
	}

	/**
	 * Checks whether a given document requires patching. <p>
	 *
	 * @param dbObject
	 * @throws UnknownDocumentVersionException if the version of the given document is unknown
	 *
	 * @return
	 */
	boolean requiresPatching(BasicDBObject dbObject) {
		int documentVersion = getDocumentVersion(dbObject);
		verifyKnownVersion(documentVersion, dbObject);
		return documentVersion != getCurrentVersion();
	}

	private void verifyKnownVersion(int documentVersion, BasicDBObject dbObject) {
		if (!isKnownVersion(documentVersion)) {
			throw new UnknownDocumentVersionException(String.format("Unknown document version %s, oldest known version is: %s, current version is : %s. document=%s",
					documentVersion, getOldestKnownVersion(), getCurrentVersion(), dbObject));
		}
	}

	int getDocumentVersion(BasicDBObject dbObject) {
		return dbObject.getInt(DOCUMENT_FORMAT_VERSION_PROPERTY, 1);
	}

	void setDocumentVersion(BasicDBObject dbObject, int version) {
		dbObject.put(DOCUMENT_FORMAT_VERSION_PROPERTY, version);
	}

	void setDocumentAttributes(BasicDBObject dbObject, T spaceObject) {
		setDocumentVersion(dbObject);
		if (loadDocumentsRouted) {
			setRoutingKey(dbObject, spaceObject);
		}
	}

	/**
	 * Checks whether a given document requires patching. <p>
	 *
	 * @param document
	 * @throws UnknownDocumentVersionException if the version of the given document is unknown
	 *
	 * @return
	 */
	boolean requiresPatching(Document document) {
		int documentVersion = getDocumentVersion(document);
		verifyKnownVersion(documentVersion, document);
		return documentVersion != getCurrentVersion();
	}

	private void verifyKnownVersion(int documentVersion, Document document) {
		if (!isKnownVersion(documentVersion)) {
			throw new UnknownDocumentVersionException(String.format("Unknown document version %s, oldest known version is: %s, current version is : %s. document=%s",
																	documentVersion, getOldestKnownVersion(), getCurrentVersion(), document));
		}
	}

	int getDocumentVersion(Document document) {
		return document.getInteger(DOCUMENT_FORMAT_VERSION_PROPERTY, 1);
	}

	void setDocumentVersion(Document document, int version) {
		document.put(DOCUMENT_FORMAT_VERSION_PROPERTY, version);
	}

	void setDocumentAttributes(Document document, T spaceObject) {
		setDocumentVersion(document);
		if (loadDocumentsRouted) {
			setRoutingKey(document, spaceObject);
		}
	}

	private void setDocumentVersion(Document document) {
		document.put(DOCUMENT_FORMAT_VERSION_PROPERTY, getCurrentVersion());
	}

	private void setDocumentVersion(BasicDBObject dbObject) {
		dbObject.put(DOCUMENT_FORMAT_VERSION_PROPERTY, getCurrentVersion());
	}

	private void setRoutingKey(BasicDBObject dbObject, T spaceObject) {
		Object routingKey = routingKeyExtractor.getRoutingKey(spaceObject);
		if (routingKey != null) {
			dbObject.put(DOCUMENT_ROUTING_KEY, routingKey.hashCode());
		}
	}

	private void setRoutingKey(Document document, T spaceObject) {
		Object routingKey = routingKeyExtractor.getRoutingKey(spaceObject);
		if (routingKey != null) {
			document.put(DOCUMENT_ROUTING_KEY, routingKey.hashCode());
		}
	}

	int getCurrentVersion() {
		if (this.patchChain.isEmpty()) {
			return 1;
		}
		return this.patchChain.getLastPatchInChain().patchedVersion() + 1;
	}

	int getOldestKnownVersion() {
		if (this.patchChain.isEmpty()) {
			return getCurrentVersion();
		}
		return this.patchChain.getFirstPatchInChain().patchedVersion();
	}

	/**
	 * Patches the given document to the current version. <p>
	 *
	 * The argument document will not be mutated. <p>
	 *
	 * @param document
	 * @return
	 */
	Document patch(Document document) {
		if (!requiresPatching(document)) {
			throw new IllegalArgumentException("Document does not require patching: " + document.toString());
		}
		while (requiresPatching(document)) {
			patchToNextVersion(document);
		}
		return document;
	}

	/**
	 * Patches the given document to the next version by writing mutating the passed in document. <p>
	 *
	 * @param document
	 */
	void patchToNextVersion(Document document) {
		if (!requiresPatching(document)) {
			throw new IllegalArgumentException("Document does not require patching: " + document.toString());
		}
		BsonDocumentPatch patch = this.patchChain.getPatch(getDocumentVersion(document));
		patch.apply(document);
		setDocumentVersion(document, patch.patchedVersion() + 1);
	}

	/**
	 * Returns the name of the collection that the underlying documents will be stored in. <p>
	 *
	 * @return
	 */
	public String getCollectionName() {
		return this.collectionName;
	}

	Object getRoutingKey(T spaceObject) {
		return routingKeyExtractor.getRoutingKey(spaceObject);
	}

	boolean isKnownVersion(int documentVersion) {
		return documentVersion >= getOldestKnownVersion() && documentVersion <= getCurrentVersion();
	}

	boolean excludeFromInitialLoad() {
		return excludeFromInitialLoad;
	}

	boolean writeBackPatchedDocuments() {
    	return writeBackPatchedDocuments;
    }

	boolean loadDocumentsRouted() {
		return loadDocumentsRouted;
	}

	ReadPreference getReadPreference() {
		return readPreference;
	}

	public boolean keepPersistent() {
		return keepPersistent;
	}

	public boolean hasCustomInitialLoadTemplate() {
		return this.customInitialLoadTemplateFactory != null;
	}

	public TemplateFactory getCustomInitialLoadTemplateFactory() {
		return this.customInitialLoadTemplateFactory;
	}
}
