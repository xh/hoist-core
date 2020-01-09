/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

/**
 * Common interface to provide a customized JSON serialization, as registered/supported by the
 * Hoist JSONSerializer and the renderJSON() method on BaseController.
 *
 * Implementing classes should return an object (typically a map) from formatForJSON() suitable for
 * serializing to JSON consumers. This could involve e.g. serializing only a subset of their public
 * properties or serializing nested objects or collections as simple identifiers. The decisions
 * here will of course depend on the requirements specific to the app or use-case.
 */
public interface JSONFormat {
    Object formatForJSON();
}
