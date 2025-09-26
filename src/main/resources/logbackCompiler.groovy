//-----------------------------------------------------
// This file required by logback-groovy-config plugin to
// allow needed imports in conf/logback-config.

// This plugin is required for logback versions post v.1.2.7 to
// allow configuration via groovy scripts.
//
// See https://virtualdogbert.github.io/logback-groovy-config/
//-------------------------------------------------------------
staticStarImportsAcceptList = [
    // From default config
    'grails.util.Environment',

    // Hoist addition
    'io.xh.hoist.configuration.LogbackConfig'
]

starImportsAcceptList = [
    'io.xh.toolbox.log',
    'io.xh.hoist.configuration'
]