package io.xh.hoist.util

import io.xh.hoist.AdminStats
import io.xh.hoist.BaseService

/**
 * Simple fixed-window rate limiter with period history.
 */
class RateMonitor implements AdminStats {

    /** Length of period in ms */
    final long periodLength

    /** Maximum requests allowed during the period. */
    final long maxPeriodRequests

    /** Requests that occurred in this period. */
    long periodRequests

    /** Number of periods the rate has remained has under max. */
    long periodsInCompliance = 0

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

    void noteRequest(){
        noteRequests(1)
    }

    void noteRequests(int count) {
        periodRequests += count
        if (limitExceeded) periodsInCompliance = 0;
    }

    //---------------------
    // Implementation
    //---------------------
    private onTimer() {
        periodsInCompliance = !limitExceeded ? periodsInCompliance + 1 : 0
        periodRequests = 0
    }

    Map getAdminStats() {
        return [
            config: [periodLength: periodLength, maxPeriodRequests: maxPeriodRequests],
            periodRequests: periodRequests,
            limitExceeded: limitExceeded,
            successStreak: periodsInCompliance
        ]
    }

    List<String> getComparableAdminStats() {
        return []
    }
}
