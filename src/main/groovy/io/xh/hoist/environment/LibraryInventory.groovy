/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import java.util.jar.Manifest

/**
 * Utilities to report the libraries, and their versions, deployed with this application.
 *
 * Reads the JAR file names and manifests visible to the classloader that loaded Hoist - i.e. the
 * `BOOT-INF/lib` or `WEB-INF/lib` contents of a packaged app, or the resolved dependency JARs of
 * a local development run. Used to populate the `dependencies` and `libraries` sections of the
 * inventory report built by {@link EnvironmentService#getInventory}.
 */
class LibraryInventory {

    /**
     * Key frameworks to report by name, each mapped to a representative class from its main JAR.
     * Java, Groovy, Grails, and Hoist versions are resolved directly by `EnvironmentService`.
     */
    static final Map<String, String> FRAMEWORK_CLASSES = [
        springBoot   : 'org.springframework.boot.SpringApplication',
        spring       : 'org.springframework.core.SpringVersion',
        hibernate    : 'org.hibernate.Session',
        hazelcast    : 'com.hazelcast.core.Hazelcast',
        jackson      : 'tools.jackson.databind.ObjectMapper',
        tomcat       : 'org.apache.catalina.Server',
        micrometer   : 'io.micrometer.core.instrument.MeterRegistry',
        openTelemetry: 'io.opentelemetry.api.OpenTelemetry',
        logback      : 'ch.qos.logback.classic.Logger'
    ]

    /**
     * All JARs on the classpath, sorted by name. Each entry is a map with `name` and `version`
     * (parsed from the file name, cross-checked against the manifest), `module` (the
     * `Automatic-Module-Name` or `Bundle-SymbolicName`, if declared), and the raw `file` name.
     */
    static List<Map> scanClasspath(ClassLoader cl = defaultClassLoader) {
        Map<String, Map> byFile = [:]
        cl.getResources('META-INF/MANIFEST.MF').each { URL url ->
            def file = jarFileName(url.toString())
            if (file && !byFile.containsKey(file)) {
                byFile[file] = describeJar(file, readManifest(url))
            }
        }
        return byFile.values().sort { it.name as String }
    }

    /** Versions of the {@link #FRAMEWORK_CLASSES}, or null for any not present. */
    static Map<String, String> getFrameworkVersions(ClassLoader cl = defaultClassLoader) {
        FRAMEWORK_CLASSES.collectEntries { key, className -> [key, versionOf(className, cl)] }
    }

    /**
     * Version of the library providing a class - from its package's `Implementation-Version`,
     * falling back to parsing its JAR file name. Null if the class is not found or not versioned.
     */
    static String versionOf(String className, ClassLoader cl = defaultClassLoader) {
        try {
            def cls = Class.forName(className, false, cl),
                ret = cls.package?.implementationVersion
            if (ret) return ret

            def location = cls.protectionDomain?.codeSource?.location,
                file = location ? jarFileName(location.toString()) : null
            return file ? parseFileName(file).version : null
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Name and version of a library from a JAR, using the manifest version when it agrees with
     * the file name and a file-name heuristic otherwise. Gradle and Maven name resolved JARs
     * `{artifactId}-{version}.jar`, making the file name the most reliable source of the version
     * that was actually resolved - manifests are often absent, stale, or OSGi-reformatted.
     */
    static Map describeJar(String file, Manifest manifest) {
        def attrs = manifest?.mainAttributes,
            manifestVersion = attrs?.getValue('Implementation-Version') ?: attrs?.getValue('Bundle-Version'),
            module = attrs?.getValue('Automatic-Module-Name') ?:
                attrs?.getValue('Bundle-SymbolicName')?.tokenize(';')?.find()?.trim(),
            base = stripJarExtension(file)

        String name, version
        if (manifestVersion && base.endsWith("-$manifestVersion")) {
            name = base[0..<(base.length() - manifestVersion.length() - 1)]
            version = manifestVersion
        } else {
            def parsed = parseFileName(file)
            name = parsed.name
            version = parsed.version ?: manifestVersion
        }

        return [name: name, version: version, module: module, file: file]
    }

    /**
     * Split a JAR file name into `name` and `version`, taking the version to start at the last
     * dash-delimited token that begins with a digit - e.g. `log4j-1.2-api-2.24.3.jar` yields
     * `log4j-1.2-api` / `2.24.3`, and `guava-33.0.0-jre.jar` yields `guava` / `33.0.0-jre`.
     * Version is null if no such token follows the first.
     */
    static Map parseFileName(String file) {
        def base = stripJarExtension(file),
            tokens = base.split('-') as List<String>,
            idx = tokens.findLastIndexOf { it ==~ /\d.*/ }

        return idx > 0 ?
            [name: tokens[0..<idx].join('-'), version: tokens[idx..-1].join('-')] :
            [name: base, version: null]
    }

    /**
     * File name of the JAR containing a resource or class, from its URL - supports plain
     * `file:` and `jar:` URLs as well as Spring Boot `jar:nested:` URLs for packaged apps.
     * Null if the URL does not resolve to a JAR (e.g. an exploded classes directory).
     */
    static String jarFileName(String url) {
        def path = url
            .replaceFirst(/!\/META-INF\/MANIFEST\.MF$/, '')
            .replaceFirst(/!?\/$/, '')
        def ret = path.substring(path.lastIndexOf('/') + 1)
        return ret.endsWith('.jar') ? ret : null
    }

    //------------------------
    // Implementation
    //------------------------
    private static ClassLoader getDefaultClassLoader() {
        LibraryInventory.classLoader
    }

    private static Manifest readManifest(URL url) {
        try {
            return url.openStream().withCloseable { new Manifest(it) }
        } catch (Exception ignored) {
            return null
        }
    }

    private static String stripJarExtension(String file) {
        file.endsWith('.jar') ? file[0..<(file.length() - 4)] : file
    }
}
