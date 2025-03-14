package io.xh.hoist

/**
 * Interface for objects, including all Hoist services, that can return a payload of data suitable
 * for display within the Hoist Admin Console's "Cluster > Services" and "Cluster > Objects" tabs.
 *
 * The primary use case for app developers is to override one or both of these methods on services,
 * returning any interesting summary data that might be useful to see on your service from the Admin
 * Console for troubleshooting or monitoring, without having to write custom controller endpoints.
 */
interface AdminStats {

    /**
     * Stats to report to the Hoist Admin client.
     * @returns a JSON serializable map.
     */
    Map getAdminStats()


    /**
     * Keys for all stats returned by getAdminStats() that should be compared for equality across
     * multiple instances in a cluster. This drives the "Cross-instance comparisons" reporting in
     * the Admin Console "Cluster > Objects" tab.
     *
     * Override this method on services if you a) are running in a multi-instance cluster, and
     * b) have stats with values that can be reliably compared and are expected to be the same
     * across all instances.
     */
    List<String> getComparableAdminStats()

}
