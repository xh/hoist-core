package io.xh.hoist.util

import io.xh.hoist.AdminStats
import io.xh.hoist.BaseService

/**
 * Simple fixed-window rate limiter with period history.
 */
class RateMonitor implements AdminStats {

    /** Length of period in ms */
    final long periodLength

    private long _maxPeriodRequests
    private long _periodRequests = 0
    private long _periodsInCompliance = 0
    private Timer _timer

    /** Requests that occurred in this period */
    long getPeriodRequests() {
        return _periodRequests
    }

    /** Maximum requests allowed during the period */
    long getMaxPeriodRequests() {
        return _maxPeriodRequests
    }

    /** Periods for which the rate has not exceeded the maximum */
    long getPeriodsInCompliance() {
        return _periodsInCompliance
    }

    void setMaxPeriodRequests(long maxPeriodRequests) {
        this._maxPeriodRequests = maxPeriodRequests
        if (limitExceeded) _periodsInCompliance = 0;
    }

    /** Has the max rate been exceeded during the period **/
    boolean getLimitExceeded() {
        return periodRequests > maxPeriodRequests
    }

    RateMonitor(String name, long maxPeriodRequests, long periodLength, BaseService owner) {
        this.periodLength = periodLength
        this._maxPeriodRequests = maxPeriodRequests
        this._timer = owner.createTimer(name: name, runFn: this.&onTimer, interval: periodLength)
    }

    void noteRequest(){
        noteRequests(1)
    }

    void noteRequests(int count) {
        _periodRequests += count
        if (limitExceeded) _periodsInCompliance = 0;
    }

    //---------------------
    // Implementation
    //---------------------
    private onTimer() {
        _periodsInCompliance = !limitExceeded ? _periodsInCompliance + 1 : 0
        _periodRequests = 0
    }

    Map getAdminStats() {
        return [
            config: [periodLength: periodLength, maxPeriodRequests: maxPeriodRequests],
            periodRequests: periodRequests,
            limitExceeded: limitExceeded,
            periodsInCompliance: periodsInCompliance
        ]
    }

    List<String> getComparableAdminStats() {
        return []
    }
}
