package io.xh.hoist.util

import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterResult
import io.xh.hoist.cluster.ClusterTask
import org.codehaus.groovy.runtime.MethodClosure
import static io.xh.hoist.util.Utils.getClusterService

class ClusterUtils {

    /**
     * Run a service method on a specific cluster instance.
     *
     *  @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     */
    static ClusterResult runOnInstance(Closure c, String instance, List args = []) {
        clusterService.submitToInstance(createTask(c, args, false), instance)
    }

    /**
     * Run a service method on a specific cluster instance, returning the values as Json.
     *
     *  @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     */
    static ClusterResult runOnInstanceAsJson(Closure c, String instance, List args = []) {
        clusterService.submitToInstance(createTask(c, args, true), instance)
    }


    /**
     * Run a service method on the primary instance, returning the result or an exception.
     *
     * @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     */
    static ClusterResult runOnPrimary(Closure c, List args = []) {
        runOnInstance(c, clusterService.primaryName, args)
    }

    /**
     * Run a service method on the primary instance, returning the result or a serialized exception
     * in Json Form.
     *
     * @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     */
    static ClusterResult runOnPrimaryAsJson(Closure c, List args = []) {
        runOnInstanceAsJson(c, clusterService.primaryName, args)
    }

    /**
     * Run a service method on *all* cluster instances.
     *
     * @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     *
     * Renders a Map of instance name to ClusterResponse.  Re
     */
    static Map<String, ClusterResult> runOnAllInstances(Closure c, List args = []) {
        clusterService.submitToAllInstances(createTask(c, args, false)) as Map<String, ClusterResult>
    }

    /**
     * Run a service method on *all* cluster instance, returning results as JSON
     *
     * @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     *
     * Renders a Map of instance name to ClusterResponse.
     */
    static Map<String, ClusterResult> runOnAllInstancesAsJson(Closure c, List args = []) {
        clusterService.submitToAllInstances(createTask(c, args, true)) as Map<String, ClusterResult>
    }

    private static ClusterTask createTask(Closure c, List args, boolean asJson) {
        if (!(c instanceof MethodClosure) || !(c.owner instanceof BaseService)) {
            throw new RuntimeException(
                "Closure must be a MethodClosure defined on an instance of BaseService. " +
                    "Please try the following syntax: `myBaseService.&methodName`"
            )
        }
        return new ClusterTask(c.owner as BaseService, c.method, args, asJson)
    }
}
