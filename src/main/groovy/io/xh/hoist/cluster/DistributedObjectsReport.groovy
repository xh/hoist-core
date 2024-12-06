package io.xh.hoist.cluster

import groovy.transform.MapConstructor
import io.xh.hoist.json.JSONFormat

@MapConstructor
class DistributedObjectsReport implements JSONFormat {

    final List<DistributedObjectInfo> info
    final Long timestamp

    Map formatForJSON() {
        return [
            info              : info,
            timestamp         : timestamp
        ]
    }
}