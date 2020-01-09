/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import io.xh.hoist.json.JSON
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
    
    Object externalValue(Map opts = [:]) {
        if (value == null) return null
        switch ( valueType ) {
            case 'json':    return opts.jsonAsObject ? JSON.parse(value) : value
            case 'int':     return value.toInteger()
            case 'long':    return value.toLong()
            case 'double':  return value.toDouble()
            case 'bool':    return value.toBoolean()
            case 'pwd' :
                if (opts.obscurePassword)       return '*********';
                if (opts.digestPassword)        return digestPassword(value);
                if (opts.decryptPassword)       return decryptPassword(value);
            default:        return value
        }
    }

    //--------------------------------------
    // Implementation
    //--------------------------------------
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

    private static String digestPassword(value) {
        // Format that will allow these values to be correctly compared in the admin config differ,
        digestEncryptor.encryptPassword(decryptPassword(value))
    }

    private static String decryptPassword(value) {
        encryptor.decrypt(value)
    }

    Map formatForJSON() {
        return [
                id           : id,
                name         : name,
                groupName    : groupName,
                valueType    : valueType,
                value        : externalValue(digestPassword: true),
                clientVisible: clientVisible,
                note         : note,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated  : lastUpdated
        ]
    }
}
