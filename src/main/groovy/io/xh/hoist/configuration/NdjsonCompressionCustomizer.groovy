/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.configuration

import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory
import org.springframework.boot.web.server.Compression
import org.springframework.boot.web.server.WebServerFactoryCustomizer

/**
 * Adds the NDJSON MIME type to the embedded container's compressible types, so that
 * {@link io.xh.hoist.BaseController#renderNDJSON} responses are compressed as expected.
 *
 * Spring Boot's default `server.compression.mimeTypes` list does not include
 * `application/x-ndjson`. This matters for local development only - deployed apps compress at the
 * xh-nginx layer, which already covers the type. Note this customizer does not *enable*
 * compression: apps must still opt in via `server.compression.enabled`.
 *
 * Appends to (rather than replaces) the list in effect, preserving both Boot's own defaults and
 * any app-level `mimeTypes` override. Boot applies those settings in its
 * ServletWebServerFactoryCustomizer, ordered 0 - this customizer declares no order, so it sorts
 * last and always runs after.
 */
class NdjsonCompressionCustomizer
    implements WebServerFactoryCustomizer<AbstractConfigurableWebServerFactory> {

    static final String NDJSON_MIME_TYPE = 'application/x-ndjson'

    void customize(AbstractConfigurableWebServerFactory factory) {
        Compression compression = factory.compression
        if (!(NDJSON_MIME_TYPE in compression.mimeTypes)) {
            compression.mimeTypes += NDJSON_MIME_TYPE
        }
    }
}
