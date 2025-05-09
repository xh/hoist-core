package io.xh.hoist.track

import io.xh.hoist.AdminStats
import io.xh.hoist.BaseService
import io.xh.hoist.util.Timer

/**
 * Simple fixed-window rate limiter with period history.
 */
class RateMonitor implements AdminStats {

    /** Length of period in ms */
    final long periodLength

    /** Maximum Requests allowed during the period. */
    final long maxPeriodRequests

    /** Requests that occurred in this period. */
    long periodRequests

    /** Number of periods the rate has remained has under max. */
    long successStreak = 0

    /** Has the max rate been exceeded during the period **/
    boolean getLimitExceeded() {
        return periodRequests > maxPeriodRequests
    }

    private Timer timer


    RateMonitor(String name, long maxPeriodRequests, long periodLength, BaseService owner) {
        this.maxPeriodRequests = maxPeriodRequests
        this.periodLength = periodLength
        this.timer = owner.createTimer(name: name, runFn: this.&onTimer, interval: periodLength)
    }

    void noteRequest(int numbRequests = 1) {
        periodRequests += numbRequests
        if (limitExceeded) successStreak = 0;
    }

    //---------------------
    // Implementation
    //---------------------
    private onTimer() {
        successStreak = !limitExceeded ? successStreak + 1 : 0
        periodRequests = 0
    }

    Map getAdminStats() {
        return [
            config: [periodLength: periodLength, maxPeriodRequests: maxPeriodRequests],
            periodRequests: periodRequests,
            limitExceeded: limitExceeded,
            successStreak: successStreak
        ]
    }

    List<String> getComparableAdminStats() {
        return []
    }
}
