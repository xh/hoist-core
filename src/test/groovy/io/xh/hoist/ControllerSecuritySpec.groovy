/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.web.Action
import io.xh.hoist.test.HoistAssertions
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import spock.lang.Specification

import java.lang.reflect.Modifier

/**
 * Hoist denies (403) any controller action that lacks an access annotation - on the method or
 * the class - but nothing flags this at startup. This spec asserts that every concrete controller
 * shipped with hoist-core has all of its actions secured.
 */
class ControllerSecuritySpec extends Specification {

    def 'controllers are discovered'() {
        expect: 'guards against the scan silently finding nothing'
        controllerClasses.size() > 20
        controllerClasses.contains(io.xh.hoist.admin.ConfigAdminController)
        controllerClasses.contains(io.xh.hoist.impl.XhController)
    }

    def 'actions are detected on the compiled controllers'() {
        expect: 'guards against the action check passing vacuously'
        controllerClasses.sum { it.methods.count { it.isAnnotationPresent(Action) } } > 50
        controllerClasses.every { c -> c.methods.any { it.isAnnotationPresent(Action) } }
    }

    def '#controller.simpleName has all actions secured'() {
        expect:
        HoistAssertions.assertAllActionsSecured(controller)

        where:
        controller << controllerClasses
    }

    /** All concrete classes named *Controller under io.xh.hoist, found on the classpath. */
    static List<Class> getControllerClasses() {
        def resolver = new PathMatchingResourcePatternResolver(),
            readers = new CachingMetadataReaderFactory(resolver)

        resolver.getResources('classpath*:io/xh/hoist/**/*Controller.class')
            .collect { readers.getMetadataReader(it).classMetadata.className }
            .unique()
            .sort()
            .collect { Class.forName(it) }
            .findAll { !Modifier.isAbstract(it.modifiers) && !it.isInterface() }
    }
}
