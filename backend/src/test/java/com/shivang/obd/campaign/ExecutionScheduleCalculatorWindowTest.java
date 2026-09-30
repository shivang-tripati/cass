package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-8J — calling-window arithmetic.
 *
 * <p>VB-8I found the window was computed once at attempt creation and never
 * re-evaluated, so a 09:00-19:00 campaign dialled at 19:01 and at 03:00. These
 * tests pin the replacement, which is a pure function of (schedule, now) and is
 * therefore tested here with an explicitly supplied instant rather than against
 * the wall clock.
 */
class ExecutionScheduleCalculatorWindowTest {

    private final ExecutionScheduleCalculator calculator = new ExecutionScheduleCalculator();

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static ScheduleSpec daily(String zone, LocalTime from, LocalTime to) {
        ScheduleSpec s = new ScheduleSpec();
        s.setTimezone(zone);
        s.setStartTime(from);
        s.setEndTime(to);
        return s;
    }

    @Nested
    @DisplayName("isWithinWindow")
    class IsWithinWindow {

        @Test
        @DisplayName("a schedule with no window is always open (unchanged behaviour)")
        void noWindowIsAlwaysOpen() {
            ScheduleSpec blank = new ScheduleSpec();
            blank.setTimezone("UTC");
            assertThat(calculator.isWithinWindow(blank, at("2026-09-29T03:00:00Z"))).isTrue();
            assertThat(calculator.isWithinWindow(null, at("2026-09-29T03:00:00Z"))).isTrue();
        }

        @Test
        @DisplayName("inside the window is open; before and after are closed")
        void insideBeforeAfter() {
            ScheduleSpec s = daily("UTC", LocalTime.of(9, 0), LocalTime.of(19, 0));
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T12:00:00Z"))).isTrue();
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T08:59:00Z"))).isFalse();
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T19:01:00Z"))).isFalse();
        }

        @Test
        @DisplayName("an overnight window is handled as one window, not two")
        void overnightWindow() {
            // 22:00 -> 02:00 spans midnight.
            ScheduleSpec s = daily("UTC", LocalTime.of(22, 0), LocalTime.of(2, 0));
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T23:00:00Z"))).isTrue();
            assertThat(calculator.isWithinWindow(s, at("2026-09-30T01:00:00Z"))).isTrue();
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T12:00:00Z"))).isFalse();
        }

        @Test
        @DisplayName("the snapshot timezone decides, not UTC or the JVM zone")
        void snapshotTimezoneIsAuthoritative() {
            // Same instant, same window hours, different declared zones.
            // 2026-09-29T12:00Z is 12:00 in UTC and 02:00 in Kiritimati (UTC+14).
            ScheduleSpec utc = daily("UTC", LocalTime.of(12, 0), LocalTime.of(13, 0));
            ScheduleSpec kiritimati = daily("Pacific/Kiritimati", LocalTime.of(12, 0),
                    LocalTime.of(13, 0));
            Instant now = at("2026-09-29T12:00:00Z");

            assertThat(calculator.isWithinWindow(utc, now)).isTrue();
            assertThat(calculator.isWithinWindow(kiritimati, now))
                    .as("02:00 local is outside a 12:00-13:00 window")
                    .isFalse();
        }

        @Test
        @DisplayName("a disallowed weekday is closed")
        void disallowedWeekday() {
            ScheduleSpec s = daily("UTC", LocalTime.of(0, 0), LocalTime.of(23, 59));
            s.setAllowedDaysOfWeek(Set.of(java.time.DayOfWeek.MONDAY));
            // 2026-09-29 is a Tuesday.
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T12:00:00Z"))).isFalse();
            assertThat(calculator.isWithinWindow(s, at("2026-09-28T12:00:00Z"))).isTrue();
        }

        @Test
        @DisplayName("a schedule that started long ago is still eligible — there is no end date")
        void noExpiryEverClosesTheWindow() {
            // VB-8J: campaign scheduling has no final end date. A campaign becomes
            // eligible at its start date and stays eligible across every later
            // calling window until its work is exhausted, so a start date months
            // in the past must never close the window by itself.
            ScheduleSpec s = daily("UTC", LocalTime.of(9, 0), LocalTime.of(19, 0));
            s.setStartDate(java.time.LocalDate.of(2026, 1, 1));
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T12:00:00Z")))
                    .as("an old start date does not expire the schedule")
                    .isTrue();
            assertThat(calculator.isWithinWindow(s, at("2031-01-01T12:00:00Z")))
                    .as("and it stays eligible arbitrarily far into the future")
                    .isTrue();
        }

        @Test
        @DisplayName("a start date in the future is still closed")
        void futureStartDateIsClosed() {
            ScheduleSpec s = daily("UTC", LocalTime.of(0, 0), LocalTime.of(23, 59));
            s.setStartDate(java.time.LocalDate.of(2026, 12, 1));
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T12:00:00Z"))).isFalse();
            assertThat(calculator.isWithinWindow(s, at("2026-12-01T12:00:00Z"))).isTrue();
        }

        @Test
        @DisplayName("a window with no resolvable zone is closed, never guessed open")
        void unresolvableZoneIsClosed() {
            ScheduleSpec s = daily(null, LocalTime.of(0, 0), LocalTime.of(23, 59));
            assertThat(calculator.isWithinWindow(s, at("2026-09-29T12:00:00Z"))).isFalse();
        }
    }

    @Nested
    @DisplayName("nextWindowOpen")
    class NextWindowOpen {

        @Test
        @DisplayName("empty when no window is configured")
        void emptyWhenNoWindow() {
            ScheduleSpec blank = new ScheduleSpec();
            blank.setTimezone("UTC");
            assertThat(calculator.nextWindowOpen(blank, at("2026-09-29T12:00:00Z"))).isEmpty();
        }

        @Test
        @DisplayName("inside the window it returns the same instant")
        void returnsNowWhenOpen() {
            ScheduleSpec s = daily("UTC", LocalTime.of(9, 0), LocalTime.of(19, 0));
            Instant now = at("2026-09-29T12:00:00Z");
            assertThat(calculator.nextWindowOpen(s, now)).contains(now);
        }

        @Test
        @DisplayName("after close it points at the next day's opening time")
        void nextDayAfterClose() {
            ScheduleSpec s = daily("UTC", LocalTime.of(9, 0), LocalTime.of(19, 0));
            var next = calculator.nextWindowOpen(s, at("2026-09-29T19:30:00Z"));
            assertThat(next).isPresent();
            assertThat(next.get()).isEqualTo(at("2026-09-30T09:00:00Z"));
        }

        @Test
        @DisplayName("before open it points at today's opening time")
        void todayBeforeOpen() {
            ScheduleSpec s = daily("UTC", LocalTime.of(9, 0), LocalTime.of(19, 0));
            var next = calculator.nextWindowOpen(s, at("2026-09-29T07:00:00Z"));
            assertThat(next).contains(at("2026-09-29T09:00:00Z"));
        }

        @Test
        @DisplayName("while closed, the result is always strictly after now (no churn)")
        void alwaysStrictlyAfterNow() {
            Instant now = at("2026-09-29T12:00:00Z");
            ScheduleSpec[] closed = {
                // wrapping window, closed during the day
                daily("UTC", LocalTime.of(22, 0), LocalTime.of(2, 0)),
                // narrow window in a far-ahead zone, so 12:00Z is the small hours
                daily("Pacific/Kiritimati", LocalTime.of(0, 0), LocalTime.of(1, 0)),
            };
            for (ScheduleSpec s : closed) {
                assertThat(calculator.isWithinWindow(s, now))
                        .as("precondition: %s is closed at this instant", s.getTimezone())
                        .isFalse();
                assertThat(calculator.nextWindowOpen(s, now).orElseThrow())
                        .as("schedule %s", s.getTimezone())
                        .isAfter(now);
            }

            // A schedule whose start date is still in the future is closed for the same
            // reason: the adjustment lands in the past, and the attempt must not
            // be reselected on the very next tick.
            ScheduleSpec future = daily("UTC", LocalTime.of(0, 0), LocalTime.of(23, 59));
            future.setStartDate(java.time.LocalDate.of(2026, 12, 1));
            assertThat(calculator.isWithinWindow(future, now)).isFalse();
            assertThat(calculator.nextWindowOpen(future, now).orElseThrow())
                    .as("a not-yet-started schedule still yields a future instant")
                    .isAfter(now);
        }

        @Test
        @DisplayName("while open, it returns now so the caller does not defer")
        void returnsNowWhileOpen() {
            ScheduleSpec open = daily("UTC", LocalTime.of(0, 0), LocalTime.of(23, 59));
            Instant now = at("2026-09-29T12:00:00Z");
            assertThat(calculator.isWithinWindow(open, now)).isTrue();
            assertThat(calculator.nextWindowOpen(open, now))
                    .as("an open window must not push the attempt forward")
                    .contains(now);
        }

        @Test
        @DisplayName("the deferred instant is in the schedule's own zone")
        void honoursSnapshotZone() {
            ScheduleSpec s = daily("Pacific/Kiritimati", LocalTime.of(9, 0), LocalTime.of(19, 0));
            // 10:00Z is 00:00 on 2026-09-30 in Kiritimati (UTC+14) - before that
            // window opens, so the next opening is 09:00 local that day, i.e.
            // 19:00Z on 2026-09-29. Asserting the LOCAL time is what proves the
            // snapshot zone was used rather than UTC, which would have said 09:00Z.
            var next = calculator.nextWindowOpen(s, at("2026-09-29T10:00:00Z"));
            assertThat(next).contains(at("2026-09-29T19:00:00Z"));
            LocalTime local = next.orElseThrow()
                    .atZone(ZoneId.of("Pacific/Kiritimati")).toLocalTime();
            assertThat(local).isEqualTo(LocalTime.of(9, 0));
        }
    }
}
