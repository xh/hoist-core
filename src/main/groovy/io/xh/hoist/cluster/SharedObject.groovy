package io.xh.hoist.cluster

import static io.xh.hoist.util.Utils.appContext

class SharedObject<T extends Serializable> {

    private Map<String, T> mp

    SharedObject(String name) {
        mp = appContext.clusterService.getReplicatedMap('shared_' + name)
    }

    T get() {
        mp.current
    }

    void set(T value) {
        mp.current = value
    }
}
