package io.xh.hoist.util

import groovy.transform.NamedParam
import groovy.transform.NamedVariant
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
    @NamedVariant
    static ClusterResult runOnInstance(
        Closure c,
        @NamedParam(required = false) List args = [],
        @NamedParam(required = false) boolean asJson = false,
        @NamedParam(required = true) String instance
    ) {
        def task = createTask(c, args, asJson)
        clusterService.submitToInstance(task, instance)
    }

    /**
     * Run a service method on the primary instance, returning the result or throwing
     * a serialized exception.
     *
     * @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     */
    @NamedVariant
    static ClusterResult runOnPrimary(
        Closure c,
        @NamedParam(required = false) List args = [],
        @NamedParam(required = false) boolean asJson = false
    ) {
        runOnInstance(c, args: args, asJson: asJson, instance: clusterService.primaryName)
    }

    /**
     * Run a service method on *all* cluster instance.
     *
     * @param c, MethodClosure for a method on a BaseService instance.
     *      Use the groovy .& operator for convenient access, e.g. `fooService.&bar`
     *
     * Renders a Map of instance name to ClusterResponse.
     */
    static Map<String, ClusterResult> runOnAllInstances(
        Closure c,
        @NamedParam(required = false) List args = [],
        @NamedParam(required = false) boolean asJson = false
    ) {
        def task = createTask(c, args, asJson)
        clusterService.submitToAllInstances(task) as Map<String, ClusterResult>
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
