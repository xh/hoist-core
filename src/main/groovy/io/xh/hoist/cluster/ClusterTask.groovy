package io.xh.hoist.cluster

import io.xh.hoist.BaseService

import io.xh.hoist.log.LogSupport

import java.util.concurrent.Callable

import io.xh.hoist.util.Utils
import static io.xh.hoist.util.Utils.getIdentityService
import static io.xh.hoist.util.Utils.getClusterService
import static io.xh.hoist.util.Utils.getAppContext
import static io.xh.hoist.json.JSONSerializer.serialize

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
            clusterService.ensureRunning()
            def clazz = Class.forName(svc),
                service = appContext.getBean(clazz),
                value = service.invokeMethod(method, args.toArray()),
                valueIsVoid = value !== null ? false : clazz.methods.find{it.name == method}?.returnType == Void.TYPE

            return new ClusterResult(value: asJson && !valueIsVoid ? serialize(value) : value)

        } catch (Throwable t) {
            Utils.handleException(
                exception: t,
                logTo: this,
                logMessage: [_action: this.class.simpleName]
            )
            return new ClusterResult(exception: new ClusterTaskException(t))
        } finally {
            identityService.threadUsername.set(null)
            identityService.threadAuthUsername.set(null)
        }
    }
}