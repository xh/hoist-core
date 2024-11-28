/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception


/**
 *  Wrapper Exception to be returned from a failed ClusterResponse.
 *
 *  Exceptions are supposed to be serializable but can cause problems in practice, especially in
 *  Kryo.  Note that we have already typically logged the actual exception on remote server.
 */
class ClusterExecutionException extends Exception {
    String causeName

    ClusterExecutionException(String msg, Throwable t) {
        super(msg)
        causeName = t.class.simpleName
    }
}
