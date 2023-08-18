package io.xh.hoist.cluster


import io.xh.hoist.util.Utils
import org.apache.hc.core5.http.HttpStatus

import java.util.concurrent.Callable

import static io.xh.hoist.json.JSONSerializer.serialize

abstract class ClusterTask implements Callable<ClusterResponse>, Serializable {

    ClusterResponse call() {
        try {
            def result = doCall()
            return new ClusterResponse(status: HttpStatus.SC_OK, result: serialize(result))
        } catch (Throwable t) {
            return Utils.appContext.exceptionRenderer.handleClusterTaskException(t)
        }
    }

    abstract Object doCall()
}


