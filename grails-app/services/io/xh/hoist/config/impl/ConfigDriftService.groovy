/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config.impl

import grails.compiler.GrailsCompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.TypedConfigMap

/**
 * Internal support service for ConfigService's typed-config registration flow.
 * Compares a `TypedConfigMap` subclass's declared property defaults against the BootStrap
 * `defaultValue` map for the same config and logs a WARN listing any drift.
 *
 * @internal - not intended for direct use by applications.
 */
@GrailsCompileStatic
class ConfigDriftService extends BaseService {

    void checkTypedConfigDivergence(
        String confName,
        Class<? extends TypedConfigMap> typedClass,
        Map bootstrapDefault
    ) {
        if (!bootstrapDefault) return

        def sample = typedClass.getDeclaredConstructor(Map).newInstance([:])
        def divergences = collectDivergences(sample.formatForJSON(), bootstrapDefault, typedClass.simpleName)
        if (divergences) {
            logWarn(
                "Typed config defaults diverge from BootStrap for '$confName'",
                divergences.join(' | '),
                "align BootStrap defaultValue with property initializers on ${typedClass.simpleName}"
            )
        }
    }

    // Recursively compare a typed-class's declared defaults (a Map that may contain nested
    // TypedConfigMap instances) against a BootStrap defaultValue Map. Produces a flat list of
    // divergence messages.
    private List<String> collectDivergences(Map typedDefaults, Map bootstrapDefault, String typedClassName, String pathPrefix = '') {
        List<String> divergences = []
        typedDefaults.each { k, tv ->
            if (!bootstrapDefault.containsKey(k)) return
            def bv = bootstrapDefault[k]
            String path = pathPrefix ? "${pathPrefix}.${k}" : "${k}"

            // Typed submap vs. Map in bootstrap → recurse. Empty bootstrap map is the
            // accepted convention for "typed class fully owns the nested shape".
            if (tv instanceof TypedConfigMap && bv instanceof Map) {
                if (bv) divergences.addAll(collectDivergences(tv.formatForJSON(), bv, typedClassName, path))
                return
            }
            // List containing TypedConfigMap vs. List in bootstrap → compare element-wise.
            // Needed because TypedConfigMap instances don't override equals, so a direct List
            // comparison would always report divergence for the same-shape typed list.
            if (tv instanceof List && bv instanceof List && tv.any { it instanceof TypedConfigMap }) {
                List tvList = tv, bvList = bv
                if (tvList.size() != bvList.size()) {
                    divergences << "'$path': typedClass has ${tvList.size()} element(s), bootstrap has ${bvList.size()}".toString()
                } else {
                    tvList.eachWithIndex { tvElem, i ->
                        def bvElem = bvList[i]
                        String elemPath = "${path}[${i}]"
                        if (tvElem instanceof TypedConfigMap && bvElem instanceof Map) {
                            divergences.addAll(collectDivergences(tvElem.formatForJSON(), bvElem, typedClassName, elemPath))
                        } else if (tvElem != bvElem) {
                            divergences << "'$elemPath': typedClass=${tvElem} bootstrap=${bvElem}".toString()
                        }
                    }
                }
                return
            }
            // Map containing TypedConfigMap values vs. Map in bootstrap → compare entry-wise,
            // for the same equals/identity reason as the List branch above.
            if (tv instanceof Map && bv instanceof Map && tv.values().any { it instanceof TypedConfigMap }) {
                Map<Object, Object> tvMap = tv, bvMap = bv
                def extraInTyped = tvMap.keySet() - bvMap.keySet()
                def extraInBoot = bvMap.keySet() - tvMap.keySet()
                extraInTyped.each { divergences << "'${path}.${it}': present on typedClass, missing from bootstrap".toString() }
                extraInBoot.each { divergences << "'${path}.${it}': present in bootstrap, missing from typedClass".toString() }
                tvMap.each { mk, tvElem ->
                    if (!bvMap.containsKey(mk)) return
                    def bvElem = bvMap[mk]
                    String elemPath = "${path}.${mk}"
                    if (tvElem instanceof TypedConfigMap && bvElem instanceof Map) {
                        divergences.addAll(collectDivergences(tvElem.formatForJSON(), bvElem, typedClassName, elemPath))
                    } else if (tvElem != bvElem) {
                        divergences << "'$elemPath': typedClass=${tvElem} bootstrap=${bvElem}".toString()
                    }
                }
                return
            }
            if (tv != bv) divergences << "'$path': typedClass=${tv} bootstrap=${bv}".toString()
        }
        bootstrapDefault.keySet().findAll { !typedDefaults.containsKey(it) }.each {
            String path = pathPrefix ? "${pathPrefix}.${it}" : "${it}"
            divergences << "'$path': declared in BootStrap defaultValue but not on $typedClassName".toString()
        }
        return divergences
    }
}
