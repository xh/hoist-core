package io.xh.hoist.cluster

import io.xh.hoist.BaseService
import io.xh.hoist.exception.InstanceNotAvailableException
import io.xh.hoist.log.LogSupport

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.*
import static io.xh.hoist.json.JSONSerializer.serialize
import static org.apache.hc.core5.http.HttpStatus.SC_OK

/**
 * Serializable task for executing remote service calls across the cluster.
 *
 * Includes support for trampolining verified user identity to remote server, and
 * structured exception handling.
 */
class ClusterTask implements Callable<ClusterResult>, LogSupport {

    final String svc
    final String method
    final List args
    final String username
    final String authUsername
    final boolean asJson

    ClusterTask(BaseService svc, String method, List args, boolean asJson) {
        this.svc = svc.class.name
        this.method = method
        this.args = args ?: []
        this.asJson = asJson
        username = identityService.username
        authUsername = identityService.authUsername
    }

    ClusterResult call() {
        identityService.threadUsername.set(username)
        identityService.threadAuthUsername.set(authUsername)

        try {
            if (!instanceReady) {
                throw new InstanceNotAvailableException('Instance not available and may be initializing.')
            }
            def service = appContext.getBean(Class.forName(svc)),
                value = service.invokeMethod(method, args.toArray())
            return asJson ?
                new ClusterResult(value: [httpStatus: SC_OK, json: serialize(value)]) :
                new ClusterResult(value: value)

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
            return asJson ?
                new ClusterResult(value: [httpStatus: exceptionHandler.getHttpStatus(t), json: serialize(t)] ):
                new ClusterResult(exception: t)
        } finally {
            identityService.threadUsername.set(null)
            identityService.threadAuthUsername.set(null)
        }
    }
}


