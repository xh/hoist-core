/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhActivityTrackingConfig` soft config, governing built-in
 * Activity Tracking via `TrackService`. Defaults declared here are applied when the backing
 * AppConfig is missing individual keys, and are the source of truth for the payload served
 * to client code (which reads the same config via `XH.getConf('xhActivityTrackingConfig')`).
 */
class ActivityTrackingConfig extends TypedConfigMap {

    /** Master switch for activity tracking. When false, no entries are recorded. */
    boolean enabled = true

    /**
     * Whether to include entry `data` payloads in the server log in addition to the DB row.
     * Accepts `true`/`false` or a `List<String>` of top-level keys to include (useful when
     * most data is noisy but specific fields are worth logging).
     */
    Object logData = false

    /** Maximum length (characters) of a serialized `data` payload persisted to the DB. */
    Integer maxDataLength = 2000

    /** Maximum entries received per-instance per minute before rate limiting kicks in. */
    Long maxEntriesPerMin = 1000L

    /**
     * Severity rules applied to incoming entries. First matching rule wins. Entries are
     * persisted only if their severity is at or above the matched rule's severity.
     */
    List<TrackLogLevel> levels = [new TrackLogLevel([username: '*', category: '*', severity: 'INFO'])]

    /** Configures client-driven periodic health-check track entries. */
    ClientHealthReport clientHealthReport = new ClientHealthReport([:])

    /** Governs Admin Console track-log query limits. */
    MaxRows maxRows = new MaxRows([:])

    ActivityTrackingConfig(Map args) { init(args) }

    /**
     * A single severity-rule entry in the `levels` list.
     *
     * `username` and `category` accept `'*'` (match all) or a comma-delimited list of values —
     * a rule matches when the incoming entry's username is contained in `username` and its
     * category is contained in `category`. `severity` is the minimum severity persisted for
     * matching entries (`DEBUG` | `INFO` | `WARN` | `ERROR`).
     */
    static class TrackLogLevel extends TypedConfigMap {
        /** `'*'` to match all, or a comma-delimited list of usernames. */
        String username = '*'
        /** `'*'` to match all, or a comma-delimited list of categories. */
        String category = '*'
        /** Minimum severity to persist for matches — `DEBUG` | `INFO` | `WARN` | `ERROR`. */
        String severity = 'INFO'

        TrackLogLevel(Map args) { init(args) }
    }

    /** Nested options for client health reports. */
    static class ClientHealthReport extends TypedConfigMap {
        /** Interval (minutes) between client health-check track entries. -1 disables. */
        Integer intervalMins = -1

        ClientHealthReport(Map args) { init(args) }
    }

    /**
     * Nested options for admin track-log query result caps.
     *
     * The external config key is `default` (a Groovy reserved word); the field is named
     * `defaultValue` internally and translated at apply/serialize time.
     */
    static class MaxRows extends TypedConfigMap {
        /** Default row cap when the caller doesn't request a specific value. */
        Integer defaultValue = 10000
        /** Hard upper bound — a caller-provided `maxRows` is clamped to this ceiling. */
        Integer limit = 25000
        /** Row-count choices offered in the Admin Console UI. */
        List<Integer> options = [1000, 5000, 10000, 25000]

        MaxRows(Map args) { init(args) }

        @Override
        protected void init(Map args) {
            if (args?.containsKey('default')) {
                args = new LinkedHashMap(args)
                args['defaultValue'] = args.remove('default')
            }
            super.init(args)
        }

        @Override
        Map formatForJSON() {
            def map = super.formatForJSON()
            if (map.containsKey('defaultValue')) {
                map['default'] = map.remove('defaultValue')
            }
            return map
        }
    }
}
