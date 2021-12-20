//------------------------------------------------------------
// This file is an example of the logback.groovy file that should
// be placed in the applications 'grails-app/conf/logback.groovy'.
// It is the standard logback file with some special setup for Hoist.
//
// For more info on Logback and the DSL behind this file
// see http://logback.qos.ch/manual/groovy.html
//
// For more info on the Hoist-related conventions, see
// LogUtils.groovy.
//------------------------------------------------------------

// 1) Change Hoist's built-in Layouts
// Example here changes monitor logging to include the day in its date format
// Do *before* initConfig() below
LogUtils.dailyLayout = '%d{yyyy-MM-dd HH:mm:ss} | %c{0} [%p] | %m%n'

//  Set up default Hoist Logging conventions
LogUtils.initConfig(this)

// 2) Change default logging levels - ROOT default is 'warn'
logger('com.mycompany', INFO)
logger('com.mycompany.mychattyservice', ERROR)

// 3) Setup dedicated logs and route specific java packages to them.
// Example here sets up a monthly rolling log for order related activity.
LogUtils.monthlyLog(name: 'order-tracking', script: this)
logger('com.mycompany.orders.orderservice', INFO, ['order-tracking'])


// 4) Create a json formatted log.
//
//  Include the following in build.gradle.  Alternatively, consider logstash-logback-encoder
//  compile "ch.qos.logback.contrib:logback-json-classic:0.1.5"
//  compile "ch.qos.logback.contrib:logback-jackson:0.1.5"
// def jsonLayout = {
//    def ret = new JsonLayout()
//    ret.jsonFormatter = new JacksonJsonFormatter()
//    ret.jsonFormatter.prettyPrint = true
//    ret.timestampFormat = 'yyyy-MM-dd HH:mm:ss.SSS'
//    return ret
// }
// LogUtils.monthlyLog(name: 'json-log', layout: jsonLayout,  script: this)
// logger('com.mycompany.foo', INFO, ['json-log'])



