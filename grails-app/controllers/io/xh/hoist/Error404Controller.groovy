package io.xh.hoist

import io.xh.hoist.exception.NotFoundException

class Error404Controller extends BaseController{
    def index() {
        def ex = new NotFoundException("The requested resource was not found. Path: ${request.forwardURI}")
        handleException(ex)
    }
}
