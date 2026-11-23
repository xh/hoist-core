/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.json.JSONFormat

/**
 * One numbered publish of a {@link Blueprint}. Its content and number never change once created:
 * only its `note`, `label` and `pinned` may, as its writers manage the history. The unique index on
 * blueprint and number keeps any number from being created twice.
 */
class BlueprintPublish implements JSONFormat {

    /** The settings a writer may change after publishing. */
    static final List<String> MUTABLE = ['note', 'label', 'pinned']

    int number
    String content
    String renames = '[]'
    String note
    String label
    boolean pinned = false
    String publishedBy
    Date dateCreated

    static belongsTo = [blueprint: Blueprint]

    static mapping = {
        table 'xh_blueprint_publish'
        version false
        number column: 'publish_number'
        blueprint index: 'idx_xh_blueprint_publish_blueprint'
        content type: 'text'
        renames type: 'text'
        note type: 'text'
    }

    static constraints = {
        number unique: 'blueprint'
        note nullable: true
        label nullable: true, maxSize: 50
        publishedBy nullable: true, maxSize: 50
    }

    def beforeUpdate() {
        List changed = dirtyPropertyNames - MUTABLE
        if (changed) {
            throw new IllegalStateException("A BlueprintPublish keeps its $changed once created.")
        }
    }

    Map formatForJSON() {[
        id: id,
        number: number,
        note: note,
        label: label,
        pinned: pinned,
        publishedBy: publishedBy,
        dateCreated: dateCreated
    ]}
}
