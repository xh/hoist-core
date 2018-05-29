/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils
import org.jasypt.util.password.ConfigurablePasswordEncryptor
import org.jasypt.util.text.BasicTextEncryptor
import org.jasypt.util.text.TextEncryptor

class AppConfig implements JSONFormat {

    static private final TextEncryptor encryptor = createEncryptor()
    static private final ConfigurablePasswordEncryptor digestEncryptor = createDigestEncryptor()

    static List TYPES = ['string', 'int', 'long', 'double', 'bool', 'json', 'pwd']

    String name
    String value
    String valueType = 'string'
    String note
    boolean clientVisible = false
    String lastUpdatedBy
    Date lastUpdated
    String groupName = 'Default'

    static mapping = {
        table 'xh_config'
        cache true
        value type: 'text'
    }

    static constraints = {
        name(unique: true, nullable: false, blank: false, maxSize: 50)
        value(nullable: false, blank: false, validator: AppConfig.isValid)
        valueType(inList: AppConfig.TYPES, validator: AppConfig.isTypeValid)
        note(nullable: true, maxSize: 1200)
        lastUpdatedBy(nullable: true, maxSize: 50)
        groupName(nullable: false, blank: false)
    }

    static isValid = { String val, AppConfig obj ->

        if (obj.valueType == 'bool' && !(val.equals('true') || val.equals('false')))
            return 'default.invalid.boolean.message'
        if (obj.valueType == 'int' && !val.isInteger())
            return 'default.invalid.integer.message'
        if (obj.valueType == 'long' && !val.isLong())
            return 'default.invalid.long.message'
        if (obj.valueType == 'double' && !val.isDouble())
            return 'default.invalid.double.message'
        if (obj.valueType == 'json' && !Utils.isJSON(val)) {
            return 'default.invalid.json.message'
        }

        return true
    }

    static String decryptPassword(String val) {
        return encryptor.decrypt(val)
    }

    static isTypeValid = { String val, AppConfig obj ->
        return (
            AppConfig.isValid(obj.value, obj).is(true)
        )
    }

    def beforeInsert() {encryptIfPwd(true)}
    def beforeUpdate() {encryptIfPwd(false)}

    private encryptIfPwd(boolean isInsert) {
        if (valueType == 'pwd' && (hasChanged('value') || isInsert)) {
            value = encryptor.encrypt(value)
        }
    }


    // Hoist admin receives all AppConfigs. Those containing pwd values should be a digest for security.
    // To allow these values to be correctly compared in the admin config differ, the stored password
    // is decrypted before being converted into a digest.
    private String maskIfPwd(String value) {
        return (valueType == 'pwd' && value != null) ? digestEncryptor.encryptPassword(decryptPassword(value)) : value
    }

    private static TextEncryptor createEncryptor() {
        def ret = new BasicTextEncryptor()
        ret.setPassword('dsd899s_*)jsk9dsl2fd223hpdj32))I@333')
        return ret
    }

    private static ConfigurablePasswordEncryptor createDigestEncryptor() {
        def ret = new ConfigurablePasswordEncryptor()
        ret.setPlainDigest(true)
        ret
    }

    Map formatForJSON() {
        return [
                id: id,
                name: name,
                groupName: groupName,
                valueType: valueType,
                value: maskIfPwd(value),
                clientVisible: clientVisible,
                note: note,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated: lastUpdated
        ]
    }

}
