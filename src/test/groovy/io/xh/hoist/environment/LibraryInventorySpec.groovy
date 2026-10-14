/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import spock.lang.Specification

import java.util.jar.Attributes
import java.util.jar.Manifest

class LibraryInventorySpec extends Specification {

    def 'parses name and version from #file'() {
        expect:
        LibraryInventory.parseFileName(file) == [name: name, version: version]

        where:
        file                                  | name                  | version
        'hazelcast-5.7.0.jar'                 | 'hazelcast'           | '5.7.0'
        'hibernate-core-5.6.15.Final.jar'     | 'hibernate-core'      | '5.6.15.Final'
        'guava-33.0.0-jre.jar'                | 'guava'               | '33.0.0-jre'
        'log4j-1.2-api-2.24.3.jar'            | 'log4j-1.2-api'       | '2.24.3'
        'bcprov-jdk18on-1.78.jar'             | 'bcprov-jdk18on'      | '1.78'
        'hoist-core-44.0-SNAPSHOT.jar'        | 'hoist-core'          | '44.0-SNAPSHOT'
        'mssql-jdbc-12.8.1.jre11.jar'         | 'mssql-jdbc'          | '12.8.1.jre11'
        'ojdbc11.jar'                         | 'ojdbc11'             | null
        'tools.jar'                           | 'tools'               | null
    }

    def 'resolves jar file name from #desc url'() {
        expect:
        LibraryInventory.jarFileName(url) == expected

        where:
        desc           | url                                                                                   | expected
        'plain jar'    | 'jar:file:/opt/app/lib/hazelcast-5.7.0.jar!/META-INF/MANIFEST.MF'                     | 'hazelcast-5.7.0.jar'
        'boot nested'  | 'jar:nested:/opt/app/app.war/!WEB-INF/lib/groovy-5.0.1.jar!/META-INF/MANIFEST.MF'     | 'groovy-5.0.1.jar'
        'code source'  | 'jar:nested:/opt/app/app.jar/!BOOT-INF/lib/jackson-databind-3.0.1.jar!/'              | 'jackson-databind-3.0.1.jar'
        'file'         | 'file:/opt/tomcat/webapps/app/WEB-INF/lib/hoist-core-43.0.0.jar'                      | 'hoist-core-43.0.0.jar'
        'classes dir'  | 'file:/workspace/app/build/classes/groovy/main/'                                      | null
    }

    def 'prefers manifest version when it matches the file name'() {
        expect:
        LibraryInventory.describeJar('spock-core-2.4-M4-groovy-4.0.jar', manifest('2.4-M4-groovy-4.0')) ==
            [name: 'spock-core', version: '2.4-M4-groovy-4.0', module: null, file: 'spock-core-2.4-M4-groovy-4.0.jar']
    }

    def 'falls back to file name when manifest version differs, and to manifest when file is unversioned'() {
        expect:
        LibraryInventory.describeJar('guava-33.0.0-jre.jar', manifest('33.0.0.jre', 'com.google.guava')).with {
            name == 'guava' && version == '33.0.0-jre' && module == 'com.google.guava'
        }
        LibraryInventory.describeJar('ojdbc11.jar', manifest('23.3.0.23.09')).version == '23.3.0.23.09'
        LibraryInventory.describeJar('ojdbc11.jar', null).version == null
    }

    def 'scans the classpath for libraries and resolves framework versions'() {
        when:
        def libs = LibraryInventory.scanClasspath()
        def frameworks = LibraryInventory.frameworkVersions

        then:
        libs.find { it.name == 'hazelcast' }?.version
        libs == libs.sort(false) { it.name }
        frameworks.hazelcast == libs.find { it.name == 'hazelcast' }.version
        frameworks.keySet() == LibraryInventory.FRAMEWORK_CLASSES.keySet()
        LibraryInventory.versionOf('com.example.DoesNotExist') == null
    }

    private static Manifest manifest(String version, String symbolicName = null) {
        def ret = new Manifest()
        ret.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, '1.0')
        ret.mainAttributes.put(Attributes.Name.IMPLEMENTATION_VERSION, version)
        if (symbolicName) ret.mainAttributes.putValue('Bundle-SymbolicName', "$symbolicName;singleton:=true")
        return ret
    }
}
