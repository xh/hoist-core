package io.xh.hoist.cluster

import io.xh.hoist.exception.InstanceNotAvailableException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils

import java.util.concurrent.Callable

import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.util.Utils.isInstanceReady
import static org.apache.hc.core5.http.HttpStatus.SC_OK

abstract class ClusterJsonRequest implements Callable<ClusterJsonResponse>, LogSupport {

    ClusterJsonResponse call() {
        try {
            if (!instanceReady) {
                throw new InstanceNotAvailableException('Instance not available and may be initializing.')
            }
            def value = doCall()
            new ClusterJsonResponse(
                httpStatus: SC_OK,
                json: serialize(value)
            )
        } catch (Throwable t) {
            def exceptionHandler = Utils.exceptionHandler
            try {
                exceptionHandler.handleException(
                    exception: t,
                    logTo: this,
                    logMessage: [_action: this.class.simpleName]
                )
            } catch (Exception e) {
                // Even logging failing -- just catch quietly and return neatly to calling member.
            }
            return new ClusterJsonResponse(
                httpStatus: exceptionHandler.getHttpStatus(t),
                json: serialize(t)
            )
        }
    }

    abstract Object doCall()
}


