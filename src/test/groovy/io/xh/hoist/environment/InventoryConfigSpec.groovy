/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import io.xh.hoist.test.HoistSpec

class InventoryConfigSpec extends HoistSpec {

    def 'defaults are disabled and token-protected'() {
        when:
        def cfg = new InventoryConfig(args)

        then:
        !cfg.enabled
        cfg.requireToken
        cfg.excludeSections == []
        cfg.usageDays == 30
        !cfg.includeDatabaseUrl
        cfg.cacheMins == 10
        cfg.tags == [:]

        where:
        args << [null, [:]]
    }

    def 'supplied values override defaults'() {
        when:
        def cfg = new InventoryConfig([
            enabled        : true,
            requireToken   : false,
            excludeSections: ['libraries', 'usage'],
            usageDays      : 7,
            tags           : [team: 'Risk Tech', assetId: 'APP-1234']
        ])

        then:
        cfg.enabled
        !cfg.requireToken
        cfg.excludeSections == ['libraries', 'usage']
        cfg.usageDays == 7
        cfg.tags == [team: 'Risk Tech', assetId: 'APP-1234']
    }

    def 'rejects a non-boolean enabled flag'() {
        when:
        new InventoryConfig([enabled: 'true'])

        then:
        thrown(IllegalArgumentException)
    }
}
