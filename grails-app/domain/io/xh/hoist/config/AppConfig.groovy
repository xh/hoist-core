/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.InstanceConfigUtils
import io.xh.hoist.util.Utils
import org.jasypt.util.password.ConfigurablePasswordEncryptor
import org.jasypt.util.text.BasicTextEncryptor
import org.jasypt.util.text.TextEncryptor

import static grails.async.Promises.task

class AppConfig implements JSONFormat, LogSupport {

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
        def overrideValue = overrideValue(opts)
        return overrideValue != null ? overrideValue : parseValue(value, opts)
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
    def beforeUpdate() {
        encryptIfPwd(false)

        // Note:  Use beforeUpdate instead of afterUpdate, because easier to identify. This is post validation
        // notify is called in a new thread and with a delay to make sure the change has had the time to propagate
        if (hasChanged('value')) {
            task {
                Thread.sleep(500)
                Utils.configService.fireConfigChanged(this)
            }
        }
    }

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

    private Object overrideValue(Map opts = [:]) {
        String overrideValue = InstanceConfigUtils.getInstanceConfig(name)
        if (overrideValue == null) return null
        try {
            if (name.endsWithIgnoreCase('password') && valueType != 'pwd') {
                throw new RuntimeException('Attempt to override non-password config')
            }
            parseValue(overrideValue, opts)
        } catch (Throwable e) {
            logTrace("Error parsing override value for config '$name'", e.message)
            return null
        }
    }

    private Object parseValue(String value, Map opts = [:]) {
        boolean isOverride = value != this.value
        switch ( valueType ) {
            case 'json':    return opts.jsonAsObject ? JSONParser.parseObjectOrArray(value) : value
            case 'int':     return value.toInteger()
            case 'long':    return value.toLong()
            case 'double':  return value.toDouble()
            case 'bool':    return value.toBoolean()
            case 'pwd' :
                if (opts.obscurePassword)       return '*********';
                if (opts.digestPassword)        return digestPassword(value, !isOverride)
                if (opts.decryptPassword)       return !isOverride ? decryptPassword(value) : value
            default:        return value
        }
    }

    private static String digestPassword(value, boolean isEncrypted) {
        // Format that will allow these values to be correctly compared in the admin config differ,
        digestEncryptor.encryptPassword(isEncrypted ? decryptPassword(value) : value)
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
                value        : parseValue(value, [digestPassword: true]),
                overrideValue: overrideValue(digestPassword: true),
                clientVisible: clientVisible,
                note         : note,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated  : lastUpdated
        ]
    }
}
