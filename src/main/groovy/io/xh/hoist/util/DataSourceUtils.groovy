/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import groovy.transform.CompileStatic
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource
import org.apache.tomcat.jdbc.pool.DataSource as PooledDataSource
import org.springframework.jdbc.datasource.DelegatingDataSource

import javax.sql.DataSource

@CompileStatic
class DataSourceUtils {

    /**
     * Walk the proxy/wrap chain rooted at {@code ds} and return the underlying Tomcat JDBC pool,
     * or {@code null} if none is found.
     *
     * Looks through Spring's {@link DelegatingDataSource} chain and the JDBC tracing layer
     * ({@link OpenTelemetryDataSource}, whose wrapped pool is held in a private {@code delegate}
     * field with no public accessor). Standard JDBC {@code unwrap}/{@code isWrapperFor} can't be
     * used here because Tomcat's {@code DataSourceProxy} stubs both to always return null/false.
     */
    static PooledDataSource findPooledDataSource(DataSource ds) {
        while (ds != null) {
            if (ds instanceof PooledDataSource) return ds
            ds = nextLayer(ds)
        }
        return null
    }

    private static DataSource nextLayer(DataSource ds) {
        if (ds instanceof DelegatingDataSource) return ds.targetDataSource
        if (ds instanceof OpenTelemetryDataSource) {
            def f = OpenTelemetryDataSource.getDeclaredField('delegate')
            f.accessible = true
            return f.get(ds) as DataSource
        }
        return null
    }
}
