/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
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

            hoist {
                enableWebSockets = false
            }

            spring {
                main.'allow-bean-definition-overriding' = true
                main.'allow-circular-references' = true
                groovy.template.'check-template-location' = false
                devtools.restart.exclude = ['grails-app/conf/**']
            }

            management {
                endpoints.'enabled-by-default' = false
            }

            grails {
                project.groupId = appPackage
                app.context = '/'
                resources.pattern = '/**'

                profile = 'rest-api'
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
                    defaultScope = 'singleton'

                    // Increase limits to 20mb to support large grid exports, other file uploads.
                    upload {
                        maxFileSize = 20971520
                        maxRequestSize = 20971520
                    }
                }

                urlmapping.cache.maxsize = 1000
                converters.encoding = 'UTF-8'
                enable.native2ascii = true
                web.disable.multipart = false
                exceptionresolver.params.exclude = ['password', 'pin']

                gorm {
                    reactor.events = false
                    failOnError = true
                }
            }

            hazelcast {
                jcache {
                    provider {
                        type = 'member'
                    }
                }
            }

            hibernate {
                javax {
                    cache {
                        provider = 'com.hazelcast.cache.impl.HazelcastServerCachingProvider'
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
                        factory_class =  'org.hibernate.cache.jcache.JCacheRegionFactory'
                    }
                }
                show_sql = true
            }
        }
    }
}
