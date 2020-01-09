/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.rx

import groovy.transform.CompileStatic
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import io.xh.hoist.util.Utils

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import io.reactivex.functions.Function

@CompileStatic
class ReactiveUtils {

    /**
     * Helper method for creating an Observable with Hibernate Context and timeout support.
     *
     * Params:
     *      timeout -- time to allow for operation, default null
     *      hibernate -- setup a hibernate session for this operation, default true
     *      subscribeOn -- scheduler to use for subscribeOn, default Schedulers.io()
     *      observeOn -- scheduler to use for observeOn, default null
     *
     *      Note that the names 'subscribeOn' and 'observeOn' are quite counter-intuitive:
     *
     *      subscribeOn defines the thread pool that the Observable will use to *produce*
     *      values and is typically what we want to modify for server-side concurrency.
     *
     *      observeOn defines the thread pool that the Observer will use to *consume* the
     *      values in its callbacks. We are often content with a single thread here, as we
     *      are joining the received values in any case for the GUI or calling code. Non-thread
     *      safe clients (e.g GUIs), may wish to explicitly force all received values onto a
     *      single thread for thread safety.
     */
    static Observable createObservable(Map options, Closure c) {
        Long timeout = (Long) options.timeout
        boolean hibernate = options.containsKey('hibernate') ? options.hibernate : true
        Scheduler subscribeOn = (Scheduler) options.subscribeOn ?: Schedulers.io()
        Scheduler observeOn = (Scheduler) options.observeOn

        // 1) Wrap the function.  Need to handle hibernate, and also
        // provide a default return of true, for app convenience.
        def fn = {
            def val = hibernate ? Utils.withNewSession(c) : c()
            return val != null ? val : true
        }

        Observable ret = Observable.fromCallable(fn)

        // 2) Implement timeout
        // We catch and rethrow the timeout just to provide the missing message
        if (timeout) {
            ret = ret.timeout(timeout, TimeUnit.MILLISECONDS)
                    .onErrorResumeNext((Function) {Throwable e ->
                        if (e instanceof TimeoutException) {
                            e = new TimeoutException("Timed out after $timeout milliseconds")
                        }
                        return Observable.error(e)
                    })
         }

        if (subscribeOn) {
            ret = ret.subscribeOn(subscribeOn)
        }

        if (observeOn) {
            ret = ret.observeOn(observeOn)
        }
        
        return ret
    }
    
}
