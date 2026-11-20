/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.json.JSONFormat

/**
 * One numbered publish of a {@link Blueprint}, which never changes once created. The unique index
 * on blueprint and number keeps any number from being created twice.
 */
class BlueprintPublish implements JSONFormat {

    int number
    String content
    String renames = '[]'
    String note
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
        publishedBy nullable: true, maxSize: 50
    }

    def beforeUpdate() {
        throw new IllegalStateException('A BlueprintPublish never changes once created.')
    }

    Map formatForJSON() {[
        id: id,
        number: number,
        note: note,
        publishedBy: publishedBy,
        dateCreated: dateCreated
    ]}
}
