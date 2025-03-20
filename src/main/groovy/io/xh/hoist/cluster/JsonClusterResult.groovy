package io.xh.hoist.cluster

class JsonClusterResult implements BaseClusterResult {
    String value
    boolean valueIsVoid
    String exception
    Integer exceptionStatusCode
}