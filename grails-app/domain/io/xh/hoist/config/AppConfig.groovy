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
import io.xh.hoist.security.crypto.ConfigValueDigester
import io.xh.hoist.security.crypto.LegacyJasyptDecrypter
import io.xh.hoist.util.InstanceConfigUtils
import io.xh.hoist.util.Utils

import static grails.async.Promises.task

class AppConfig implements JSONFormat, LogSupport {

    // Active encryption for `pwd`-typed config values uses an app-supplied key sourced from
    // instance config (env var `APP_{appCode}_APP_CONFIG_CRYPTO_KEY` or YAML). Apps that use
    // `pwd` configs must configure this key; without it, `pwd` reads still work (legacy values
    // are unaffected) but `pwd` writes fail closed. See v41 upgrade notes.
    private static final AesTextCipher activeCipher = createActiveCipher()

    // Pre-v41 jasypt obfuscation key. Public-by-design: appeared in this open-source file's
    // source for years, kept verbatim solely so `LegacyJasyptDecrypter` can read pre-upgrade
    // `pwd` values for the one-release migration window. NEVER used to encrypt new content —
    // {@link #activeCipher} (configured with an app-supplied key) handles all v41+ writes.
    // gitleaks:allow pragma: allowlist secret
    private static final String LEGACY_OBFUSCATION_KEY = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'
    private static final LegacyJasyptDecrypter legacyDecrypter = new LegacyJasyptDecrypter(LEGACY_OBFUSCATION_KEY)

    private static final ConfigValueDigester digestEncryptor = new ConfigValueDigester()

    private static AesTextCipher createActiveCipher() {
        String key = InstanceConfigUtils.getInstanceConfig('appConfigCryptoKey')
        return key ? new AesTextCipher(key) : null
    }

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
            if (activeCipher == null) {
                throw new IllegalStateException(
                    "Cannot save pwd-typed AppConfig '$name': no encryption key configured. " +
                    "Set instance config 'appConfigCryptoKey' (typically via env var " +
                    "APP_<appCode>_APP_CONFIG_CRYPTO_KEY) to a high-entropy value held in your " +
                    "secrets manager, then restart. See v41 upgrade notes."
                )
            }
            value = activeCipher.encrypt(value)
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

    // Reads both the current AES-GCM format (written under the app-supplied key) and legacy
    // jasypt-format values (under the source-visible LEGACY_OBFUSCATION_KEY). Legacy values
    // upgrade in place the next time the AppConfig row is saved.
    private static String decryptPassword(String value) {
        if (AesTextCipher.isHoistFormat(value)) {
            if (activeCipher == null) {
                throw new IllegalStateException(
                    "Cannot decrypt pwd-typed AppConfig value: value was written under an " +
                    "app-supplied encryption key but no key is currently configured. Set " +
                    "instance config 'appConfigCryptoKey' to the same value that wrote this " +
                    "row, or restore from a pre-encryption backup. See v41 upgrade notes."
                )
            }
            return activeCipher.decrypt(value)
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
