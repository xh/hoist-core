/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import groovy.transform.CompileStatic
import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessAll

@AccessAll
@CompileStatic
class JsonBlobController extends BaseController {

    JsonBlobService jsonBlobService

    def get(int id) {
        renderJSON(jsonBlobService.get(id))
    }

    def list(String type) {
        renderJSON(jsonBlobService.list(type))
    }

    def create(String type, String name, String value, String description) {
        renderJSON(jsonBlobService.create(type, name, value, description))
    }

    def update(int id, String name, String value, String description) {
        renderJSON(jsonBlobService.update(id, name, value, description))
    }

    def delete(int id) {
        jsonBlobService.delete(id)
        renderJSON(success: true)
    }

}
