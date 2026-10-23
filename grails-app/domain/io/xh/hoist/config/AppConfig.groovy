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
import io.xh.hoist.util.InstanceConfigUtils
import io.xh.hoist.util.Utils

import java.security.MessageDigest

import static grails.async.Promises.task
import static java.nio.charset.StandardCharsets.UTF_8

class AppConfig implements JSONFormat, LogSupport {

    // Key for `pwd`-typed config values. Fixed and visible in this open-source file, so it masks
    // values in the Admin Console and DB dumps but does not protect them from anyone with
    // database access. See https://github.com/xh/hoist-core/issues/627 for app-supplied keys.
    // gitleaks:allow pragma: allowlist secret
    private static final String CRYPTO_KEY = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'
    private static final AesTextCipher cipher = new AesTextCipher(CRYPTO_KEY)

    // Reads pre-v44 jasypt-format values, which upgrade in place the next time they are saved.
    private static final LegacyJasyptDecrypter legacyDecrypter = new LegacyJasyptDecrypter(CRYPTO_KEY)

    static List TYPES = ['string', 'int', 'long', 'double', 'bool', 'json', 'pwd']

    /**
     * Placeholder value for a config that has not been set. Validation requires a non-null,
     * non-blank `value`, so a config that is bootstrapped but not yet configured holds this
     * instead. Bootstrap a config that an app may leave unset with this as its `defaultValue`, and
     * read it with {@link io.xh.hoist.config.ConfigService#getStringIfSet} / `getPwdIfSet`, which
     * map it back to null.
     */
    static final String NONE = 'none'

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
            value = cipher.encrypt(value)
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
    // Unsalted so that digests compare across environments; visible to admins only.
    private static String digestPassword(String value, boolean isEncrypted) {
        String plain = isEncrypted ? decryptPassword(value) : value
        if (plain == null) return null
        return Base64.encoder.encodeToString(MessageDigest.getInstance('SHA-256').digest(plain.getBytes(UTF_8)))
    }

    private static String decryptPassword(String value) {
        AesTextCipher.isHoistFormat(value) ? cipher.decrypt(value) : legacyDecrypter.decrypt(value)
    }

    Map formatForJSON() {
        return [
                id           : id,
                name         : name,
                groupName    : groupName,
                valueType    : valueType,
                value        : parseValue(value, [digestPassword: true]),
                overrideValue: overrideValue(digestPassword: true),
                resolvedValue: Utils.configService.getResolvedConfigValue(this),
                defaultValue : Utils.configService.getDefaultConfigValue(this),
                clientVisible: clientVisible,
                note         : note,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated  : lastUpdated
        ]
    }
}
