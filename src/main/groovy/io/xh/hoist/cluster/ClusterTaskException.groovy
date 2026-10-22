/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.cluster

import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.json.JSONSerializer

/**
 * DTO object used for transferring an exception during a ClusterTask. We serialize the cause as
 * Json, to ensure it  can be safely serialized, but also because that is ultimately the form we
 * will need it in for calls looking for JsonResults.
 */
class ClusterTaskException extends RuntimeException {
    String causeClassName
    String causeMessage
    String causeAsJson
    Integer causeStatusCode

    ClusterTaskException(Throwable cause) {
        super()
        causeClassName = cause.class.name
        causeMessage = cause.message
        causeAsJson = JSONSerializer.serialize(cause)
        causeStatusCode = ExceptionHandler.getHttpStatus(cause)
    }
}
