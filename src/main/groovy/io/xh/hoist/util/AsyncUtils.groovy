package io.xh.hoist.util

import static grails.async.Promises.task
import static grails.async.Promises.waitAll


/**
 * Hoist Tools for asynchronous tasks.
 */
class AsyncUtils {

    /**
     * Run a closure asynchronously over a collection of objects, using a Grails task.
     *
     * This method will return when all tasks have completed, and will throw the first
     * exception thrown.  See Promise.waitAll for more info.
     *
     * Optimized to avoid the async overhead if only a single item is provided.
     */
    static void asyncEach(Collection col, Closure fn) {
        def size = col.size()
        if (size == 1) {
            fn.call(col.first())
        } else if (size > 1) {
            def tasks = col.collect { c -> task { fn.call(c) } }
            waitAll(tasks)
        }
    }
}
