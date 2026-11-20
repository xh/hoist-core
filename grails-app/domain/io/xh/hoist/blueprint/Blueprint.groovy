/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.json.JSONFormat

/**
 * A blueprint's draft and metadata, managed by {@link BlueprintService}. Its numbered publishes
 * and its users' personalizations are {@link BlueprintPublish} and
 * {@link BlueprintPersonalization}, which reference it by foreign key.
 *
 * JSON content is stored as given, as serialized text. `writers` and `readers` hold lists of
 * grantees: a username, `role:<ROLE>`, or `*` for all users.
 */
class Blueprint implements JSONFormat {

    String token = UUID.randomUUID().toString().substring(0, 8)
    String name
    String description
    String owner
    String writers = '[]'
    String readers = '[]'
    String draft
    long draftVersion = 1
    String pendingRenames = '[]'
    Integer publishedVersion
    String copiedFromToken
    Long copiedFromVersion
    String copiedFromKind
    Date dateCreated
    Date lastUpdated
    String lastUpdatedBy

    static mapping = {
        table 'xh_blueprint'
        // draftVersion is the one version, and every mutation takes a row lock in BlueprintService.
        version false
        token index: 'idx_xh_blueprint_token'
        description type: 'text'
        writers type: 'text'
        readers type: 'text'
        draft type: 'text'
        pendingRenames type: 'text'
    }

    static constraints = {
        token unique: true, blank: false
        name maxSize: 255, blank: false
        description nullable: true
        owner maxSize: 50, blank: false
        publishedVersion nullable: true
        copiedFromToken nullable: true
        copiedFromVersion nullable: true
        copiedFromKind nullable: true, inList: ['draft', 'publish']
        lastUpdatedBy nullable: true
    }

    Map formatForJSON() {[
        id: id,
        token: token,
        name: name,
        description: description,
        owner: owner,
        draftVersion: draftVersion,
        publishedVersion: publishedVersion,
        dateCreated: dateCreated,
        lastUpdated: lastUpdated,
        lastUpdatedBy: lastUpdatedBy
    ]}
}
