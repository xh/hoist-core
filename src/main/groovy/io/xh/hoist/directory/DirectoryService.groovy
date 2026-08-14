/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.directory

/**
 * Contract for services that resolve "directory groups" - groups maintained in an external
 * corporate directory such as LDAP / Active Directory or Microsoft Entra ID - into their member
 * users, for use by Hoist's built-in role management.
 *
 * <p>Hoist provides two implementations: {@link io.xh.hoist.ldap.LdapService} and
 * {@link io.xh.hoist.entra.EntraIdService}. The default
 * {@link io.xh.hoist.role.provided.DefaultRoleService} selects an enabled implementation to
 * resolve directory-group-based role memberships. See that class for details on provider
 * selection.
 *
 * <p>This interface is deliberately narrow. It models only what role resolution and its Admin
 * Console UI require. Richer user and group query APIs remain on the implementing services,
 * with provider-specific types.
 */
interface DirectoryService {

    /** True if this service is configured for use. */
    boolean getEnabled()

    /**
     * Short description of the expected form of a directory group identifier, displayed as a
     * hint (e.g. tooltip) within the Admin Console UI.
     */
    String getDirectoryGroupsDescription()

    /**
     * Resolve directory group identifiers to their member users, including members of any
     * nested groups.
     *
     * <p>If strictMode is true, implementations must throw on any partial failure. Otherwise
     * they log the failure and return an error description for the affected groups.
     *
     * @param groups - directory group identifiers, in the implementation-specific form
     *      described by {@link #getDirectoryGroupsDescription}.
     * @return Map of directory group identifier to either a Set of assigned usernames (on
     *      success) or a String description of the lookup error (on failure).
     */
    Map<String, Object> loadUsersForDirectoryGroups(Set<String> groups, boolean strictMode)

    /**
     * Resolve display information for directory groups, for use by the Admin Console UI when
     * listing groups already assigned to roles.
     *
     * @return Map of directory group identifier to either a Map with a `displayName` key (plus
     *      any other implementation-specific detail keys) or a String description of the lookup
     *      error. Implementations do not throw on individual lookup failures.
     */
    Map<String, Object> describeDirectoryGroups(Set<String> groups)

    /**
     * Search for directory groups by partial name, for use by the Admin Console UI when adding
     * groups to a role.
     *
     * @return List of Maps, each with an `id` key holding the group identifier to store and a
     *      `displayName` key, plus any other implementation-specific detail keys.
     */
    List<Map> searchDirectoryGroups(String namePart)
}
