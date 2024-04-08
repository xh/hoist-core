package io.xh.hoist.cluster

import io.xh.hoist.exception.InstanceNotAvailableException
import io.xh.hoist.log.LogSupport

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.isInstanceReady
import static io.xh.hoist.util.Utils.getExceptionHandler

abstract class ClusterRequest<T> implements Callable<ClusterResponse<T>>, LogSupport {

    ClusterResponse<T> call() {
        try {
            if (!instanceReady) {
                throw new InstanceNotAvailableException('Instance not available and may be initializing.')
            }
            return new ClusterResponse(value: doCall())
        } catch (Throwable t) {
            try {
                exceptionHandler.handleException(
                    exception: t,
                    logTo: this,
                    logMessage: [_action: this.class.simpleName]
                )
            } catch (Exception e) {
                // Even logging failing -- just catch quietly and return neatly to calling member.
            }
            return new ClusterResponse(exception: t)
        }
    }

    abstract T doCall()
}


