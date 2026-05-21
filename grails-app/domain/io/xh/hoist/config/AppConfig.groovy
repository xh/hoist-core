/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONParser
import io.xh.hoist.log.LogSupport
import io.xh.hoist.security.crypto.AesTextCipher
import io.xh.hoist.security.crypto.LegacyJasyptDecrypter
import io.xh.hoist.security.crypto.SaltedSha256Digester
import io.xh.hoist.util.InstanceConfigUtils
import io.xh.hoist.util.Utils

import static grails.async.Promises.task

class AppConfig implements JSONFormat, LogSupport {

    // Hard-coded encryption password — preserved across the jasypt removal in v41 so that
    // pwd-typed config values written by hoist-core <= v40 (which used jasypt's
    // BasicTextEncryptor under this same password) remain decryptable via the legacy fallback.
    // The same string seeds the new AES-GCM cipher via PBKDF2, but new ciphertext is
    // distinguishable from legacy by the AesTextCipher.FORMAT_PREFIX marker, so the two
    // never collide. (See the v41 upgrade notes for context — sourcing this key from
    // instance config is a future enhancement.)
    private static final String CONFIG_ENCRYPTION_PASSWORD = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    static private final AesTextCipher encryptor = new AesTextCipher(CONFIG_ENCRYPTION_PASSWORD)
    static private final LegacyJasyptDecrypter legacyDecrypter = new LegacyJasyptDecrypter(CONFIG_ENCRYPTION_PASSWORD)
    static private final SaltedSha256Digester digestEncryptor = new SaltedSha256Digester()

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
        valueType(inList: AppConfig.TYPES)
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
        if (obj.valueType == 'json') {
            if (!Utils.isJSON(val)) return 'default.invalid.json.message'

            // Reject saves whose value can't populate the registered typed class.
            def typedClass = Utils.configService.getTypedClass(obj.name)
            if (typedClass) {
                try {
                    typedClass.getDeclaredConstructor(Map).newInstance(JSONParser.parseObject(val))
                } catch (Exception e) {
                    return ['default.invalid.typedConfig.message', typedClass.simpleName, e.cause?.message ?: e.message]
                }
            }
        }

        return true
    }

    Object externalValue(Map opts = [:]) {
        def override = overrideValue(opts)
        return override != null ? override : parseValue(value, opts)
    }

    //--------------------------------------
    // Implementation
    //--------------------------------------
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

    private Object overrideValue(Map opts = [:]) {
        String overrideValue = InstanceConfigUtils.getInstanceConfig(name)
        if (overrideValue == null) return null

        // We don't have any control over the string that's been set into the InstanceConfig,
        // so we don't assume it can be parsed into the required type. Log failures on trace for
        // minimal visibility, but otherwise act as if the override value is not set.
        try {
            return parseValue(overrideValue, opts)
        } catch (Throwable e) {
            logTrace("InstanceConfig override found for '$name' but cannot be parsed - override will be ignored", e.message)
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
                // Override values will not be encrypted by our local encryptor - treat as already-plaintext.
                if (opts.digestPassword)        return digestPassword(value, !isOverride)
                if (opts.decryptPassword)       return !isOverride ? decryptPassword(value) : value
            default:        return value
        }
    }

    // Allow pwd values to be compared in the admin config differ, without exposing the actual value.
    private static String digestPassword(String value, boolean isEncrypted) {
        digestEncryptor.digest(isEncrypted ? decryptPassword(value) : value)
    }

    /**
     * Decrypt a stored pwd-type value. Transparently handles both the current v41+ AES-GCM
     * format and legacy values written by hoist-core <= v40 (jasypt's PBEWithMD5AndDES).
     * Legacy values are not rewritten in-place by this read path — they upgrade to the new
     * format the next time the AppConfig row is saved (e.g. via the admin UI).
     */
    private static String decryptPassword(String value) {
        if (AesTextCipher.isHoistFormat(value)) {
            return encryptor.decrypt(value)
        }
        return legacyDecrypter.decrypt(value)
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
