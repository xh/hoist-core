package io.xh.hoist

interface AdminStats {

    /**
     * Stats to report to the Hoist Admin client.
     * @returns a JSON serializable map.
     */
    Map getAdminStats()


    /**
     * Keys for stats in getAdminStats() that can be compared for equality across instances.
     */
    List<String> getComparableAdminStats()

}