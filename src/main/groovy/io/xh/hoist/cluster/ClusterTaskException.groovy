package io.xh.hoist.cluster

import io.xh.hoist.json.JSONSerializer
import static io.xh.hoist.util.Utils.getExceptionHandler

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
        causeStatusCode = exceptionHandler.getHttpStatus(cause)
    }
}