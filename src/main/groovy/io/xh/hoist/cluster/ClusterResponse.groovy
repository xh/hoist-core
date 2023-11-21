package io.xh.hoist.cluster

class ClusterResponse<T> implements Serializable {
    T value
    Throwable exception
}


