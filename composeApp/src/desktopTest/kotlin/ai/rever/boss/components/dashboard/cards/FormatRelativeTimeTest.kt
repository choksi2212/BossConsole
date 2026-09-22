package ai.rever.boss.components.dashboard.cards

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that `formatRelativeTime`'s "Yesterday" bucket is a calendar-day
 * boundary in the supplied zone, not an elapsed-hours window, AND that the
 * absolute-date formatter uses the SAME zone the calendar classification was
 * computed in.
 *
 * Two cases were broken before this and would have shipped if anyone had
 * opened the dashboard late at night or first thing in the morning:
 *
 *  - A timestamp from 47 hours ago at 23:00 read "Yesterday" when the
 *    calendar truth was two days ago.
 *  - A timestamp from yesterday at 23:59 read "Just now" at 00:01 when the
 *    calendar truth was Yesterday.
 *
 * A third case, not in the original PR but found while wiring this up, was
 * the formatter using the host default timezone while the calendar
 * classification used the supplied one - so a timestamp classified as Sep 18
 * in the supplied zone could be displayed as Sep 17 when the host default
 * zone was a day ahead.
 *
 * `now` and `zone` are injected through the function's parameters - the only
 * public shape is the displayed string.
 */
class FormatRelativeTimeTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long {
        // Interpret (year, month, day, hour, minute) as a wall-clock time in
        // the test's zone, not in UTC. Without this every test would shift
        // by the Kolkata offset and the boundary cases would no longer
        // straddle midnight.
        return LocalDateTime
            .of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun `zero timestamp is Never`() {
        assertEquals("Never", formatRelativeTime(timestamp = 0L, now = 0L, zone = zone))
    }

    @Test
    fun `timestamp from yesterday late evening reads Yesterday at noon today`() {
        // Issue #1078, Case 2: at 23:59 last night the elapsed-time buckets
        // caught a 2-minute window and rendered "Just now" right after
        // midnight. Calendar-day logic returns "Yesterday" instead.
        val now = at(2026, 9, 20, 12, 0)
        val yesterdayLateNight = at(2026, 9, 19, 23, 59)
        assertEquals("Yesterday", formatRelativeTime(yesterdayLateNight, now, zone))
    }

    @Test
    fun `timestamp from today early morning does NOT read Yesterday at noon today`() {
        // Inverse of Case 2: same calendar day, only minutes ago.
        val now = at(2026, 9, 20, 12, 0)
        val todayEarlyMorning = at(2026, 9, 20, 0, 1)
        val result = formatRelativeTime(todayEarlyMorning, now, zone)
        assertEquals("11h ago", result)
    }

    @Test
    fun `timestamp 47 hours ago across a midnight does NOT read Yesterday`() {
        // Issue #1078, Case 1: elapsed-hours saw 47h and called it "Yesterday",
        // but the timestamp landed two calendar days before "now".
        val now = at(2026, 9, 20, 22, 0)
        val twoDaysAgoLateNight = at(2026, 9, 18, 23, 0)
        val result = formatRelativeTime(twoDaysAgoLateNight, now, zone)
        assertEquals("Sep 18", result)
    }

    @Test
    fun `timestamp exactly two calendar days ago formats the date`() {
        val now = at(2026, 9, 20, 12, 0)
        val twoDaysAgo = at(2026, 9, 18, 12, 0)
        assertEquals("Sep 18", formatRelativeTime(twoDaysAgo, now, zone))
    }

    @Test
    fun `timestamp a week ago formats the date`() {
        val now = at(2026, 9, 20, 12, 0)
        val weekAgo = at(2026, 9, 13, 12, 0)
        assertEquals("Sep 13", formatRelativeTime(weekAgo, now, zone))
    }

    @Test
    fun `timestamp from 30 seconds ago reads Just now`() {
        val now = at(2026, 9, 20, 12, 0)
        // Construct "30 seconds ago" by subtracting 30 seconds in millis.
        val thirtySecondsAgo = now - 30_000L
        assertEquals("Just now", formatRelativeTime(thirtySecondsAgo, now, zone))
    }

    @Test
    fun `timestamp from 5 minutes ago reads 5m ago`() {
        val now = at(2026, 9, 20, 12, 0)
        val fiveMinutesAgo = now - 5L * 60_000L
        assertEquals("5m ago", formatRelativeTime(fiveMinutesAgo, now, zone))
    }

    @Test
    fun `timestamp from 3 hours ago on the same day reads 3h ago`() {
        val now = at(2026, 9, 20, 12, 0)
        val threeHoursAgo = now - 3L * 3600_000L
        assertEquals("3h ago", formatRelativeTime(threeHoursAgo, now, zone))
    }

    @Test
    fun `absolute date formatter honours the supplied zone not the host default`() {
        // Pick a zone far enough from anywhere plausible as a CI runner's
        // host zone that the bug shows up in every reasonable configuration:
        // Pacific/Pago_Pago is UTC-11, so any host running UTC, UTC+0..+14,
        // or anywhere in the Americas ahead of Samoa would disagree with it.
        // Build the wall-clock times in that zone so the instants are
        // independent of the host clock.
        val remoteZone = ZoneId.of("Pacific/Pago_Pago")

        fun remoteAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            LocalDateTime
                .of(year, month, day, hour, minute)
                .atZone(remoteZone)
                .toInstant()
                .toEpochMilli()

        // In Pago Pago, the timestamp and "now" are two calendar days apart:
        // Sep 18 vs Sep 20, both at noon local. The classification is daysAgo = 2.
        val now = remoteAt(2026, 9, 20, 12, 0)
        val twoDaysAgo = remoteAt(2026, 9, 18, 12, 0)

        // The classification and the formatter MUST agree on the zone, so
        // the displayed date is Sep 18 in Pago Pago - not whatever the host
        // default happens to think those same instants are.
        assertEquals("Sep 18", formatRelativeTime(twoDaysAgo, now, remoteZone))
    }
}
