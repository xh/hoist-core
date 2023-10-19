package io.xh.hoist.cluster

import io.xh.hoist.log.LogSupport
import java.util.concurrent.Callable
import static io.xh.hoist.util.Utils.appContext

abstract class ClusterRequest<T> implements Callable<ClusterResponse<T>>, Serializable, LogSupport {

    ClusterResponse<T> call() {
        try {
            return new ClusterResponse(value: doCall())
        } catch (Throwable t) {
            appContext.xhExceptionHandler.handleException(
                exception: t,
                logTo: this,
                logMessage:  [_action: this.class.simpleName]
            )
            return new ClusterResponse(exception: t)
        }
    }

    abstract T doCall()
}


