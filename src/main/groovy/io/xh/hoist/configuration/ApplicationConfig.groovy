/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.configuration

import static io.xh.hoist.util.Utils.withDelegate
import static io.xh.hoist.util.Utils.getAppPackage

/**
 * Default Application config.
 *
 * Main entry point to be called from application.groovy
 */
class ApplicationConfig {

    static void defaultConfig(Script script) {
        withDelegate(script) {

            // Default logging level for early console logging during startup.
            // See `init/LogBack.groovy` for information on how to customize hoist logging
            logging.level.root = 'warn'
            logging.level.io.xh.hoist = 'info'

            hoist {
                // Read by WebSocketService to determine if WS support should generally be enabled.
                enableWebSockets = true
                sensitiveParamTerms = ['password', 'passwrd', 'pwd', 'secret', 'tkn', 'token']
            }

            spring {
                main.'allow-bean-definition-overriding' = true
                main.'allow-circular-references' = true
                groovy.template.'check-template-location' = false
                devtools.restart.exclude = ['grails-app/conf/**']
                // disable Spring's auto-configured equivalents to avoid duplicate
                autoconfigure.exclude = [

                    // Unused by Hoist, and would couple us to a specific Apache HttpClient version.
                    'org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration',
                    'org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration',
                    'org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration',

                    // Hoist manages its own MeterRegistry, export sinks, and HTTP metrics
                    'org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration',
                    'org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration',
                    'org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration',
                    'org.springframework.boot.actuate.autoconfigure.observation.web.servlet.WebMvcObservationAutoConfiguration'
                ]
            }

            server {
                // Spring Boot compresses only a built-in list of MIME types, which does not
                // include the NDJSON type served by `BaseController.renderNDJSON`. Boot's
                // defaults are class-level (`o.s.b.web.server.Compression`) and not visible as
                // config, so the list is restated here in full. Apps can append to it with
                // `server.compression.mimeTypes += 'foo/bar'` after calling `defaultConfig()`.
                //
                // Note this does not *enable* compression - apps opt in via
                // `server.compression.enabled`, typically in local development only, as deployed
                // apps compress at the xh-nginx layer instead.
                compression.mimeTypes = [
                    'text/html', 'text/xml', 'text/plain', 'text/css', 'text/javascript',
                    'application/javascript', 'application/json', 'application/xml',
                    'application/x-ndjson'
                ]
            }

            management {
                 endpoint {
                    health.'show-details' = 'always'
                }
            }

            grails {
                app.context = '/'

                cors {
                    enabled = true
                    allowCredentials = true
                    allowedOriginPatterns = ['*']
                }

                mime {
                    disable.accept.header.userAgents = ['Gecko', 'WebKit', 'Presto', 'Trident']
                    types = [
                        all          : '*/*',
                        atom         : 'application/atom+xml',
                        css          : 'text/css',
                        csv          : 'text/csv',
                        form         : 'application/x-www-form-urlencoded',
                        html         : ['text/html', 'application/xhtml+xml'],
                        js           : 'text/javascript',
                        json         : ['application/json', 'text/json'],
                        multipartForm: 'multipart/form-data',
                        rss          : 'application/rss+xml',
                        text         : 'text/plain',
                        hal          : ['application/hal+json', 'application/hal+xml'],
                        xml          : ['text/xml', 'application/xml'],
                        excel        : 'application/vnd.ms-excel'
                    ]
                }

                controllers {
                    // Increase limits to 20mb to support large grid exports, other file uploads.
                    upload {
                        maxFileSize = 20971520
                        maxRequestSize = 20971520
                    }
                }

                web.disable.multipart = false
                gorm {
                    failOnError = true
                }
            }

            hazelcast {
                jcache {
                    provider {
                        type = 'member'
                    }
                }
                client.statistics.enabled = true
            }

            hibernate {
                javax {
                    cache {
                        provider = 'com.hazelcast.cache.impl.HazelcastServerCachingProvider'
                        uri = 'hazelcast-hibernate.xml'
                    }
                    persistence {
                        sharedCache {
                            mode = 'ENABLE_SELECTIVE'
                        }
                    }
                }
                cache {
                    use_second_level_cache = true
                    queries = true
                    use_query_cache = true
                    generate_statistics = true
                    region {
                        factory_class = 'org.hibernate.cache.jcache.JCacheRegionFactory'
                    }
                }
                show_sql = false
            }
        }
    }
}
