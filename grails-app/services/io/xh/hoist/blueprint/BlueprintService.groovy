/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.exception.RoutineRuntimeException
import io.xh.hoist.user.HoistUser

import java.nio.charset.StandardCharsets

import static io.xh.hoist.blueprint.BlueprintPersonalization.NO_PRESET
import static io.xh.hoist.json.JSONParser.parseArray
import static io.xh.hoist.json.JSONParser.parseObject
import static io.xh.hoist.json.JSONSerializer.serialize

/**
 * Stores blueprints: a draft per blueprint, numbered publishes that never change, and each user's
 * personalizations. Off until `xhBlueprintConfig.enabled` is set - see {@link BlueprintConfig}.
 *
 * Access is checked here for every call, for the current (apparent) user:
 *   - Write access belongs to a blueprint's owner, its writers and `adminRoles`. It is needed for
 *     the draft, for any publish but the latest, and to list publishes.
 *   - Read access adds its readers, and reaches only the latest publish. A blueprint without a
 *     publish is listed only for users with write access.
 *   - Publishing needs write access, plus one of `publishRoles` if any are configured.
 *   - Deleting and managing access belong to the owner and `adminRoles`.
 *   - Publishing, sharing and deleting are refused while an admin impersonates another user.
 *   - Personalizations always belong to the current user, and need read access.
 *
 * Every call that changes a blueprint's row takes a database row lock on it first, so a draft
 * save or publish based on a draft version no longer current is refused with
 * {@link BlueprintStaleBaseException}, atomically across a cluster.
 *
 * Content is stored as given, as JSON. Validating it against the blueprint format is the client's
 * job. This service checks only its structure: JSON objects and lists of the expected shape, no
 * `__proto__`, `prototype` or `constructor` keys or ids, and `maxBytes`.
 */
class BlueprintService extends BaseService {

    static final String READ = 'read', WRITE = 'write'

    ConfigService configService

    private static final Set<String> FORBIDDEN_NAMES = ['__proto__', 'prototype', 'constructor'] as Set
    private static final Set<String> RENAME_KINDS = ['element', 'variable', 'source'] as Set
    private static final Set<String> OPEN_KINDS = ['draft', 'publish'] as Set
    private static final List<String> SUMMARY_FIELDS = [
        'token', 'name', 'description', 'owner', 'writers', 'readers', 'draftVersion',
        'publishedVersion', 'lastUpdated', 'lastUpdatedBy', 'copiedFromToken', 'copiedFromVersion',
        'copiedFromKind'
    ]

    //------------------
    // Blueprints
    //------------------
    /**
     * Summaries of the blueprints the current user may read, as {@link #summaryOf} gives them.
     * Leaves out the stored content.
     */
    @ReadOnly
    List<Map> list() {
        ensureEnabled()
        List rows = Blueprint.createCriteria().list {
            projections { SUMMARY_FIELDS.each { property(it) } }
        } as List
        return rows
            .collect { summaryOf(toMap(SUMMARY_FIELDS, it)) }
            .findAll { it.access == WRITE || (it.access == READ && it.publishedVersion != null) }
    }

    /**
     * A blueprint's metadata, with the current user's `access`, `canPublish` and `canManage`.
     * Its `writers` and `readers`, as parsed lists, are included only when `canManage`.
     *
     * @param blueprint a Blueprint, or a map of its summary fields
     */
    Map summaryOf(Object blueprint) {
        Map row = SUMMARY_FIELDS.collectEntries { [it, blueprint[it]] }
        HoistUser user = getUser()
        String access = accessOf(row.owner as String, row.writers as String, row.readers as String, user)
        boolean manage = canManage(row.owner as String, user)
        return [
            *: row.findAll { !(it.key in ['writers', 'readers']) },
            access: access,
            canPublish: access == WRITE && hasPublishRight(user),
            canManage: manage,
            canShareGlobally: manage && canShareGlobally(user)
        ] + (manage ? [writers: parseArray(row.writers as String), readers: parseArray(row.readers as String)] : [:])
    }

    /**
     * A blueprint, with its draft. Null if it does not exist or the current user lacks write
     * access, so a draft the user may not see looks the same as a missing one.
     */
    @ReadOnly
    Blueprint getDraft(String token) {
        ensureEnabled()
        Blueprint ret = Blueprint.findByToken(token)
        return ret && getAccess(ret) == WRITE ? ret : null
    }

    /**
     * A publish of a blueprint - the latest by default, which needs read access, or one by number,
     * which needs write access unless it is the latest. Null if there is none, or the current user
     * may not see it.
     */
    @ReadOnly
    BlueprintPublish getPublish(String token, Integer number = null) {
        ensureEnabled()
        Blueprint blueprint = Blueprint.findByToken(token)
        String access = blueprint ? getAccess(blueprint) : null
        if (!access) return null

        boolean latest = number == null || number == blueprint.publishedVersion
        if (!latest && access != WRITE) return null
        Integer target = latest ? blueprint.publishedVersion : number
        return target != null ? BlueprintPublish.findByBlueprintAndNumber(blueprint, target) : null
    }

    /** Every publish of a blueprint, oldest first, without its content. Requires write access. */
    @ReadOnly
    List<Map> listPublishes(String token) {
        ensureEnabled()
        Blueprint blueprint = find(token)
        ensureAccess(blueprint, WRITE)
        List fields = ['number', 'dateCreated', 'publishedBy', 'note', 'renames']
        List rows = BlueprintPublish.createCriteria().list {
            eq('blueprint', blueprint)
            projections { fields.each { property(it) } }
            order('number', 'asc')
        } as List
        return rows.collect {
            Map ret = toMap(fields, it)
            ret.renames = parseArray(ret.renames as String)
            return ret
        }
    }

    /**
     * Create a blueprint, owned by the current user, with the blueprint given as its first draft
     * and no publish. Requires one of `createRoles`.
     *
     * @param writers grantees with write access - usernames, `role:<ROLE>`, or `*`
     * @param readers grantees with read access, in the same form
     * @param copiedFrom the source this blueprint was saved from, as `{id, version, as}`. The current
     *      user must be able to open that version, as for {@link #getDraft} and {@link #getPublish}.
     */
    @Transactional
    Blueprint create(
        String name,
        String description,
        Object blueprint,
        Object writers = [],
        Object readers = [],
        Object copiedFrom = null
    ) {
        ensureEnabled()
        ensureCanCreate()
        List<String> writerList = writerGrantees(writers),
                     readerList = grantees(readers)
        ensureGlobalShareAllowed([], readerList)
        Map source = checkedSource(copiedFrom)
        new Blueprint(
            name: name,
            description: description,
            owner: username,
            writers: serialize(writerList),
            readers: serialize(readerList),
            draft: serializeContent(blueprint),
            copiedFromToken: source?.token,
            copiedFromVersion: source?.version,
            copiedFromKind: source?.kind,
            lastUpdatedBy: username
        ).save(flush: true, failOnError: true)
    }

    /**
     * Save a blueprint's draft, with the renames since its last publish. Requires write access.
     *
     * @param base the draft version this save was based on
     * @return the draft's new version
     * @throws BlueprintStaleBaseException if the draft is no longer at `base`
     */
    @Transactional
    long saveDraft(String token, Object blueprint, long base, Object renames) {
        ensureEnabled()
        String draft = serializeContent(blueprint),
               pendingRenames = serializeRenames(renames)

        Blueprint ret = lock(token)
        ensureAccess(ret, WRITE)
        ensureBase(ret, base)
        ret.draft = draft
        ret.pendingRenames = pendingRenames
        ret.draftVersion = base + 1
        ret.lastUpdatedBy = username
        ret.save(flush: true, failOnError: true)
        return ret.draftVersion
    }

    /**
     * Publish a blueprint's draft as its next number, with the renames the draft holds, which it
     * then clears. The draft's content is unchanged, but its version goes up, so a save based on
     * the draft from before the publish is refused rather than re-adding the published renames.
     * The new draft version is on the returned publish's `blueprint`.
     *
     * @param base the draft version this publish was based on
     * @throws BlueprintStaleBaseException if the draft is no longer at `base`
     */
    @Transactional
    BlueprintPublish publish(String token, long base, String note = null) {
        ensureEnabled()
        Blueprint blueprint = lock(token)
        ensureNotImpersonatedAction('publish')
        if (!canPublish(blueprint)) {
            throw new NotAuthorizedException("User '$username' may not publish blueprint '$token'")
        }
        ensureBase(blueprint, base)

        int number = (blueprint.publishedVersion ?: 0) + 1
        BlueprintPublish ret = new BlueprintPublish(
            blueprint: blueprint,
            number: number,
            content: blueprint.draft,
            renames: blueprint.pendingRenames,
            note: note ?: null,
            publishedBy: username
        ).save(flush: true, failOnError: true)

        blueprint.publishedVersion = number
        blueprint.pendingRenames = '[]'
        blueprint.draftVersion = base + 1
        blueprint.lastUpdatedBy = username
        blueprint.save(flush: true, failOnError: true)
        return ret
    }

    /**
     * Copy a blueprint into a new one, owned by the current user, whose draft is the source's
     * latest publish, or the version given. The copy records its source, and has no publish.
     * Requires one of `createRoles`, plus access to the source version, as for opening it.
     *
     * @param kind 'publish' (default) or 'draft'
     * @param number a publish number - the latest if null. Ignored for a draft.
     * @return the copy, or null if the source has no such publish
     */
    @Transactional
    Blueprint copy(String token, String name, String kind = 'publish', Integer number = null) {
        ensureEnabled()
        ensureCanCreate()
        if (!(kind in OPEN_KINDS)) throw new RoutineRuntimeException("Unknown kind '$kind' - use 'draft' or 'publish'")

        Blueprint source = find(token)
        String content
        long version
        if (kind == 'draft') {
            ensureAccess(source, WRITE)
            content = source.draft
            version = source.draftVersion
        } else {
            if (number != null && number != source.publishedVersion) ensureAccess(source, WRITE)
            BlueprintPublish publish = getPublish(token, number)
            if (!publish) return null
            content = publish.content
            version = publish.number
        }

        new Blueprint(
            name: name,
            description: source.description,
            owner: username,
            draft: content,
            copiedFromToken: token,
            copiedFromVersion: version,
            copiedFromKind: kind,
            lastUpdatedBy: username
        ).save(flush: true, failOnError: true)
    }

    /**
     * Delete a blueprint, with its publishes and every user's personalizations of it, in one
     * transaction. Requires the owner or one of `adminRoles`.
     */
    @Transactional
    void delete(String token) {
        ensureEnabled()
        Blueprint target = lock(token)
        ensureNotImpersonatedAction('delete')
        ensureCanManage(target)
        BlueprintPersonalization.where { blueprint == target }.deleteAll()
        BlueprintPublish.where { blueprint == target }.deleteAll()
        target.delete(flush: true)
    }

    /** Set a blueprint's writers and readers. Requires the owner or one of `adminRoles`. */
    @Transactional
    Blueprint updateAccess(String token, Object writers, Object readers) {
        ensureEnabled()
        List<String> writerList = writerGrantees(writers),
                     readerList = grantees(readers)

        Blueprint ret = lock(token)
        ensureNotImpersonatedAction('share')
        ensureCanManage(ret)
        ensureGlobalShareAllowed(parseArray(ret.readers), readerList)
        ret.writers = serialize(writerList)
        ret.readers = serialize(readerList)
        ret.lastUpdatedBy = username
        ret.save(flush: true, failOnError: true)
    }

    /**
     * Make sure a global blueprint with this name and owner exists and publishes the given content.
     *
     *   - If none exists, create it readable by everyone, publish its first version, and return it.
     *   - If one exists and its latest publish differs from the given content, save the content as
     *     its draft and publish it as the next version, both as `owner`, and return it.
     *   - Otherwise return it unchanged.
     *
     * A republish overwrites any edits made to the blueprint since, such as an admin's, published
     * or not, and discards its pending renames. It keeps the blueprint's description and sharing.
     *
     * For server code only, such as seeding an app's presets at startup. It bypasses every access
     * check - `createRoles`, `globalShareRoles` and the current user - so never expose it to a
     * client. `owner` need not be a real user. Run it on one instance, such as the primary, as two
     * concurrent calls for the same name can both create.
     *
     * @param owner the name recorded as the blueprint's owner, publisher and last updater
     */
    @Transactional
    Blueprint ensureGlobal(String name, String description, Object blueprint, String owner) {
        ensureEnabled()
        String draft = serializeContent(blueprint)
        Blueprint existing = Blueprint.findByNameAndOwner(name, owner, [lock: true])
        if (existing) return republishIfChanged(existing, draft, owner)

        Blueprint ret = new Blueprint(
            name: name,
            description: description,
            owner: owner,
            readers: serialize(['*']),
            draft: draft,
            draftVersion: 2,
            publishedVersion: 1,
            lastUpdatedBy: owner
        ).save(flush: true, failOnError: true)
        new BlueprintPublish(
            blueprint: ret,
            number: 1,
            content: draft,
            publishedBy: owner
        ).save(flush: true, failOnError: true)
        logInfo('Created global blueprint', [name: name, owner: owner, token: ret.token])
        return ret
    }

    private Blueprint republishIfChanged(Blueprint blueprint, String draft, String owner) {
        BlueprintPublish latest = blueprint.publishedVersion ?
            BlueprintPublish.findByBlueprintAndNumber(blueprint, blueprint.publishedVersion) : null
        if (latest && parseObject(latest.content) == parseObject(draft)) return blueprint

        int number = (blueprint.publishedVersion ?: 0) + 1
        new BlueprintPublish(
            blueprint: blueprint,
            number: number,
            content: draft,
            publishedBy: owner
        ).save(flush: true, failOnError: true)

        blueprint.draft = draft
        blueprint.pendingRenames = '[]'
        blueprint.draftVersion += 2
        blueprint.publishedVersion = number
        blueprint.lastUpdatedBy = owner
        blueprint.save(flush: true, failOnError: true)
        logInfo('Republished changed global blueprint', [name: blueprint.name, owner: owner, token: blueprint.token, number: number])
        return blueprint
    }

    //------------------
    // Personalizations
    //------------------
    /** The current user's personalizations of a blueprint. Requires read access. */
    @ReadOnly
    List<BlueprintPersonalization> listPersonalizations(String token) {
        ensureEnabled()
        Blueprint blueprint = find(token)
        ensureAccess(blueprint, READ)
        BlueprintPersonalization.findAllByBlueprintAndUsername(blueprint, username)
    }

    /**
     * Save the current user's personalization of a blueprint for a preset, replacing any for the
     * same preset. Requires read access.
     *
     * @param preset the preset as `group:item`, or null for paths no preset controls
     * @param ops `set` and `remove` ops, with JSON Pointer paths
     * @param publishVersion the publish the ops were made against
     */
    @Transactional
    BlueprintPersonalization savePersonalization(String token, Object preset, Object ops, int publishVersion) {
        ensureEnabled()
        if (preset != null && !(preset instanceof String && preset ==~ /[^:]+:[^:]+/)) {
            throw new RoutineRuntimeException("A preset must be 'group:item', got '$preset'")
        }
        String opsJson = serializeOps(ops)

        Blueprint blueprint = find(token)
        ensureAccess(blueprint, READ)
        if (publishVersion < 1 || publishVersion > (blueprint.publishedVersion ?: 0)) {
            throw new RoutineRuntimeException("Blueprint '$token' has no publish $publishVersion")
        }

        String key = (preset ?: NO_PRESET) as String
        BlueprintPersonalization ret = BlueprintPersonalization.findByBlueprintAndUsernameAndPreset(blueprint, username, key)
        if (!ret) {
            int max = config.maxPersonalizations
            if (BlueprintPersonalization.countByBlueprintAndUsername(blueprint, username) >= max) {
                throw new RoutineRuntimeException("A user may keep at most $max personalizations of one blueprint")
            }
            ret = new BlueprintPersonalization(blueprint: blueprint, username: username, preset: key)
        }
        ret.ops = opsJson
        ret.publishVersion = publishVersion
        ret.save(flush: true, failOnError: true)
    }

    //------------------
    // Access
    //------------------
    /** The current user's access to a blueprint - `write`, `read`, or null for none. */
    String getAccess(Blueprint blueprint) {
        accessOf(blueprint.owner, blueprint.writers, blueprint.readers, getUser())
    }

    /** True if the current user may publish a blueprint. */
    boolean canPublish(Blueprint blueprint) {
        getAccess(blueprint) == WRITE && hasPublishRight(getUser())
    }

    /** True if the current user may create blueprints, including copies. */
    boolean canCreate() {
        ensureEnabled()
        hasAnyRole(getUser(), config.createRoles)
    }

    //------------------
    // Implementation
    //------------------
    private BlueprintConfig getConfig() {
        configService.getObject(BlueprintConfig)
    }

    private void ensureEnabled() {
        if (!config.enabled) {
            throw new RoutineRuntimeException('Blueprint storage is not enabled - set `enabled` in the xhBlueprintConfig soft config.')
        }
    }

    // A blueprint the current user may read. One they may not read is reported as not found.
    private Blueprint find(String token) {
        readable(Blueprint.findByToken(token), token)
    }

    // As find, with a row lock (SELECT ... FOR UPDATE) held until the transaction ends.
    private Blueprint lock(String token) {
        readable(Blueprint.findByToken(token, [lock: true]), token)
    }

    private Blueprint readable(Blueprint blueprint, String token) {
        if (!blueprint || !getAccess(blueprint)) throw new BlueprintNotFoundException(token)
        return blueprint
    }

    private void ensureBase(Blueprint blueprint, long base) {
        if (blueprint.draftVersion != base) throw new BlueprintStaleBaseException(base, blueprint.draftVersion)
    }

    private void ensureAccess(Blueprint blueprint, String required) {
        String access = getAccess(blueprint)
        if (!access) throw new BlueprintNotFoundException(blueprint.token)
        if (access == WRITE || required == READ) return
        throw new NotAuthorizedException(
            "User '$username' does not have $required access to blueprint '${blueprint.token}'"
        )
    }

    private void ensureCanCreate() {
        if (!canCreate()) throw new NotAuthorizedException("User '$username' may not create blueprints")
    }

    private boolean canShareGlobally(HoistUser user) {
        hasAnyRole(user, config.adminRoles) || hasAnyRole(user, config.globalShareRoles)
    }

    // Adding `*` to readers shares with everyone, and needs globalShareRoles. Keeping or removing
    // it does not.
    private void ensureGlobalShareAllowed(List oldReaders, List newReaders) {
        boolean adding = '*' in newReaders && !('*' in oldReaders)
        if (adding && !canShareGlobally(getUser())) {
            throw new NotAuthorizedException(
                "User '$username' may not share a blueprint with everyone - that needs one of the roles in xhBlueprintConfig.globalShareRoles."
            )
        }
    }

    private boolean canManage(String owner, HoistUser user) {
        owner == user.username || hasAnyRole(user, config.adminRoles)
    }

    // Publishing, sharing and deleting are refused while impersonating, so the audit trail never
    // records an admin's change as the user's own.
    private static final Map<String, String> PAST_TENSE = [publish: 'published', share: 'shared', delete: 'deleted']

    private void ensureNotImpersonatedAction(String action) {
        if (authUsername != username) {
            throw new NotAuthorizedException(
                "Blueprints cannot be ${PAST_TENSE[action]} while impersonating another user. Stop impersonating to $action this blueprint."
            )
        }
    }

    private void ensureCanManage(Blueprint blueprint) {
        if (!canManage(blueprint.owner, getUser())) {
            throw new NotAuthorizedException("Only the owner or an admin may manage blueprint '${blueprint.token}'")
        }
    }

    private String accessOf(String owner, String writers, String readers, HoistUser user) {
        if (owner == user.username || hasAnyRole(user, config.adminRoles) || grants(writers, user)) return WRITE
        if (grants(readers, user)) return READ
        return null
    }

    private boolean grants(String grantees, HoistUser user) {
        parseArray(grantees).any { Object grantee ->
            String g = grantee as String
            g == '*' || g == user.username || (g.startsWith('role:') && user.hasRole(g.substring(5)))
        }
    }

    private boolean hasPublishRight(HoistUser user) {
        List<String> publishRoles = config.publishRoles
        !publishRoles || hasAnyRole(user, config.adminRoles) || hasAnyRole(user, publishRoles)
    }

    private boolean hasAnyRole(HoistUser user, List<String> roles) {
        roles && (roles.contains('*') || user.hasAnyRole(roles as String[]))
    }

    //------------------
    // Structural checks
    //------------------
    private String serializeContent(Object blueprint) {
        if (!(blueprint instanceof Map)) throw new RoutineRuntimeException('A blueprint must be a JSON object')
        checkNames(blueprint)
        return checkedSize(serialize(blueprint), 'blueprint')
    }

    // A source as {id, version, as}, checked as for opening it: the latest publish needs read
    // access, a draft or older publish write. A draft version may be stale, but not ahead.
    private Map checkedSource(Object source) {
        if (source == null) return null
        if (!(source instanceof Map) || !(source.id instanceof String) || !(source.version instanceof Number) || !(source.as in OPEN_KINDS)) {
            throw new RoutineRuntimeException("copiedFrom must be {id, version, as}, with as 'draft' or 'publish', got ${serialize(source)}")
        }
        String token = source.id
        long version = (source.version as Number).longValue()
        Blueprint blueprint = find(token)
        if (source.as == 'draft') {
            ensureAccess(blueprint, WRITE)
            if (version < 1 || version > blueprint.draftVersion) {
                throw new RoutineRuntimeException("Blueprint '$token' has no draft version $version")
            }
        } else {
            if (version != blueprint.publishedVersion) ensureAccess(blueprint, WRITE)
            if (!BlueprintPublish.findByBlueprintAndNumber(blueprint, version as int)) {
                throw new RoutineRuntimeException("Blueprint '$token' has no publish $version")
            }
        }
        return [token: token, version: version, kind: source.as]
    }

    private String serializeRenames(Object renames) {
        if (renames == null) return '[]'
        if (!(renames instanceof List)) throw new RoutineRuntimeException('Renames must be a list')
        renames.each { rename ->
            if (!(rename instanceof Map) || !(rename.kind in RENAME_KINDS) || !isName(rename.from) || !isName(rename.to)) {
                throw new RoutineRuntimeException("A rename must be {kind, from, to}, with kind one of ${RENAME_KINDS.join(', ')}, got ${serialize(rename)}")
            }
            [rename.from, rename.to].each { checkName(it as String) }
        }
        return serialize(renames.collect { Map r -> [kind: r.kind, from: r.from, to: r.to] })
    }

    private String serializeOps(Object ops) {
        if (!(ops instanceof List)) throw new RoutineRuntimeException('Ops must be a list')
        ops.each { op ->
            boolean valid = op instanceof Map && op.path instanceof String && (op.path as String).startsWith('/') && (
                (op.op == 'set' && op.containsKey('value') && op.keySet() == ['op', 'path', 'value'] as Set) ||
                (op.op == 'remove' && op.keySet() == ['op', 'path'] as Set)
            )
            if (!valid) {
                throw new RoutineRuntimeException("A personalization op must be {op: 'set', path, value} or {op: 'remove', path}, got ${serialize(op)}")
            }
            pointerSegments(op.path as String).each { checkName(it) }
            if (op.op == 'set') checkNames(op.value)
        }
        return checkedSize(serialize(ops), 'personalization')
    }

    private List<String> writerGrantees(Object writers) {
        List<String> ret = grantees(writers)
        if ('*' in ret) throw new RoutineRuntimeException("'*' may only be a reader - a blueprint cannot be writable by everyone")
        return ret
    }

    private List<String> grantees(Object grantees) {
        if (grantees == null) return []
        if (!(grantees instanceof List) || !grantees.every { it instanceof String && it.trim() }) {
            throw new RoutineRuntimeException('Writers and readers must be lists of usernames, `role:<ROLE>` or `*`')
        }
        return grantees.collect { (it as String).trim() }.unique() as List<String>
    }

    private void checkNames(Object value) {
        if (value instanceof Map) {
            value.each { k, v ->
                checkName(k as String)
                checkNames(v)
            }
        } else if (value instanceof List) {
            value.each { checkNames(it) }
        }
    }

    private void checkName(String name) {
        if (name in FORBIDDEN_NAMES) throw new RoutineRuntimeException("'$name' is forbidden as a key or ID")
    }

    // A projection row, as an array from Hibernate or a list from other datastores.
    private Map toMap(List fields, Object row) {
        [fields, (row instanceof Object[] ? (row as Object[]).toList() : row as List)].transpose().collectEntries()
    }

    private boolean isName(Object value) {
        value instanceof String && value
    }

    private List<String> pointerSegments(String path) {
        path.substring(1).split('/', -1).collect { it.replace('~1', '/').replace('~0', '~') }
    }

    private String checkedSize(String json, String what) {
        int max = config.maxBytes,
            size = json.getBytes(StandardCharsets.UTF_8).length
        if (size > max) throw new RoutineRuntimeException("The $what is $size bytes, over the limit of $max")
        return json
    }

    @ReadOnly
    Map getAdminStats() {[
        config: configForAdminStats('xhBlueprintConfig'),
        blueprints: Blueprint.count(),
        publishes: BlueprintPublish.count(),
        personalizations: BlueprintPersonalization.count()
    ]}
}
