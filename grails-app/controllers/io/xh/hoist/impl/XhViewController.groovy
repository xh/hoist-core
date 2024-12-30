/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.impl

import groovy.transform.CompileStatic
import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessAll
import io.xh.hoist.view.ViewService

@AccessAll
@CompileStatic
class XhViewController extends BaseController {

    ViewService viewService

    //----------------------------
    // ViewManager state + support
    //-----------------------------
    def allData(String type, String viewInstance) {
        renderJSON(viewService.getAllData(type, viewInstance))
    }

    def updateState(String type, String viewInstance) {
        viewService.updateState(type, viewInstance, parseRequestJSON())
        renderJSON([success: true])
    }

    //---------------------------
    // Individual View management
    //----------------------------
    def get(String token) {
        renderJSON(viewService.get(token))
    }

    def create() {
        renderJSON(viewService.create(parseRequestJSON()))
    }

    def delete(String tokens) {
        viewService.delete(tokens.split(',').toList())
        renderJSON([success: true])
    }

    def updateInfo(String token) {
        renderJSON(viewService.updateInfo(token, parseRequestJSON()))
    }

    def updateValue(String token) {
        renderJSON(viewService.updateValue(token, parseRequestJSON()))
    }

    def makeGlobal(String token) {
        renderJSON(viewService.makeGlobal(token))
    }
}
