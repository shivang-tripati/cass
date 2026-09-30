package com.shivang.obd.campaign;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * VB-8B: the single authoritative calling-window calculation for campaign
 * executions.
 *
 * <p>This logic used to live privately inside
 * {@code CampaignExecutionOrchestrator}, which meant only the scheduler could
 * compute an execution-valid dispatch time. VB-8A then found a second attempt
 * path — {@code CallAttemptService.createAttempt} — that had no way to honour
 * it and accepted a client-supplied {@code scheduledAt} instead.
 *
 * <p>Rather than letting a second implementation appear, the calculation was
 * extracted here so there is exactly one place that answers
 * "is this instant inside the execution's frozen window, and if not, when is".
 * Both the scheduler and the manual path now call it.
 *
 * <h2>Why this operates on the frozen schedule</h2>
 *
 * <p>Every method takes the {@link ScheduleSpec} that came from
 * {@code CampaignRuntimeConfig}, i.e. from the immutable execution snapshot.
 * There is no overload that accepts a live {@link CampaignEntity} and no
 * fallback to one: the campaign row is not a scheduling authority after
 * execution creation.
 *
 * <h2>Timezone</h2>
 *
 * <p>The snapshot's IANA zone is authoritative and there is no JVM/UTC
 * fallback. An absent or invalid zone is handled by the caller: the scheduler
 * tolerates an unset schedule, while the dial path fails deterministically via
 * {@code DailyDialLimitService.resolveUsageDate}. This component deliberately
 * does not invent a third policy for invalid zones — it propagates the
 * {@link java.time.DateTimeException} that {@link ZoneId#of} raises.
 */
@Component
public class ExecutionScheduleCalculator {

    /**
     * The earliest execution-valid instant at or after {@code baseTime} for the
     * given frozen schedule.
     *
     * <p>With no schedule, no timezone, or no configured window, the base time
     * is returned unchanged: an unrestricted schedule does not delay dispatch.
     */
    public Instant calculateNextScheduledAt(ScheduleSpec schedule, Instant baseTime) {
        if (schedule == null || isBlank(schedule.getTimezone())) {
            return baseTime;
        }
        if (!isScheduleWindowConfigured(schedule)) {
            return baseTime;
        }
        return adjustToScheduleWindow(schedule, baseTime);
    }

    /**
     * Clamps {@code proposedTime} into the frozen schedule's date range, daily
     * window and allowed days of week.
     *
     * <p>Clamping only ever moves the time <em>later</em>: a proposed time that
     * already resolves to something earlier than itself is returned unchanged
     * rather than pushed further into the past.
     */
    public Instant adjustToScheduleWindow(ScheduleSpec schedule, Instant proposedTime) {
        if (schedule == null || isBlank(schedule.getTimezone())) {
            return proposedTime;
        }

        ZoneId zone = ZoneId.of(schedule.getTimezone().trim());
        ZonedDateTime result = proposedTime.atZone(zone);

        // A campaign becomes eligible at its start date and stays eligible.
        LocalDate date = result.toLocalDate();
        if (schedule.getStartDate() != null && date.isBefore(schedule.getStartDate())) {
            result = schedule.getStartDate().atTime(result.toLocalTime()).atZone(zone);
        }

        // Ensure time is within [startTime, endTime]
        LocalTime time = result.toLocalTime();
        if (schedule.getStartTime() != null && time.isBefore(schedule.getStartTime())) {
            result = result.toLocalDate().atTime(schedule.getStartTime()).atZone(zone);
        }
        if (schedule.getEndTime() != null && time.isAfter(schedule.getEndTime())) {
            // Next day at startTime
            LocalDate nextDate = result.toLocalDate().plusDays(1);
            if (schedule.getStartTime() != null) {
                result = nextDate.atTime(schedule.getStartTime()).atZone(zone);
            } else {
                result = nextDate.atStartOfDay(zone);
            }
        }

        // Ensure day of week is allowed
        Set<DayOfWeek> allowedDays = schedule.getAllowedDaysOfWeek();
        if (allowedDays != null && !allowedDays.isEmpty()) {
            DayOfWeek day = result.getDayOfWeek();
            if (!allowedDays.contains(day)) {
                // Find next allowed day
                int daysToAdd = 1;
                while (daysToAdd <= 7) {
                    DayOfWeek nextDay = day.plus(daysToAdd);
                    if (allowedDays.contains(nextDay)) {
                        result = result.plusDays(daysToAdd);
                        break;
                    }
                    daysToAdd++;
                }
            }
        }

        // Holiday calendar is a reference only — not resolved in this phase
        // ponytail: holidayCalendarId not resolved, add when HolidayCalendar module exists

        // If adjusted time is in the past, return proposed (don't delay further)
        if (result.toInstant().isBefore(proposedTime)) {
            return proposedTime;
        }

        return result.toInstant();
    }

    /** Whether the schedule constrains dispatch at all. */
    public boolean isScheduleWindowConfigured(ScheduleSpec schedule) {
        return schedule.getStartDate() != null
            || schedule.getStartTime() != null
            || schedule.getEndTime() != null;
    }

    /**
     * VB-8J: whether {@code now} falls inside the calling window.
     *
     * <p>{@code scheduledAt} used to be computed once at attempt creation and
     * never re-evaluated, so a 09:00-19:00 campaign kept dialling at 19:01 and at
     * 03:00. Dispatch has to ask this question every time.
     *
     * <p>A schedule with no window configured is always open, so campaigns that
     * never configured calling hours behave exactly as before.
     *
     * <p>Snapshot-driven and timezone-explicit, with no JVM/UTC fallback: a wrong
     * zone here would silently dial outside the hours the operator configured.
     * An unresolvable zone reads as closed rather than open, matching the
     * fail-loud treatment timezone-invalid already gets elsewhere.
     */
    public boolean isWithinWindow(ScheduleSpec schedule, Instant now) {
        if (schedule == null || !isScheduleWindowConfigured(schedule)) {
            return true;
        }
        if (isBlank(schedule.getTimezone())) {
            return false;
        }
        ZonedDateTime moment = now.atZone(ZoneId.of(schedule.getTimezone().trim()));
        LocalDate date = moment.toLocalDate();
        LocalTime time = moment.toLocalTime();

        if (schedule.getStartDate() != null && date.isBefore(schedule.getStartDate())) {
            return false;
        }
        Set<DayOfWeek> allowedDays = schedule.getAllowedDaysOfWeek();
        if (allowedDays != null && !allowedDays.isEmpty()
                && !allowedDays.contains(date.getDayOfWeek())) {
            return false;
        }
        if (!wrapsMidnight(schedule)) {
            if (schedule.getStartTime() != null && time.isBefore(schedule.getStartTime())) {
                return false;
            }
            return schedule.getEndTime() == null || !time.isAfter(schedule.getEndTime());
        }
        // A window whose endTime precedes its startTime spans midnight, e.g.
        // 22:00-02:00. It is open outside the closed band between the two.
        return !(time.isAfter(schedule.getEndTime()) && time.isBefore(schedule.getStartTime()));
    }

    private static boolean wrapsMidnight(ScheduleSpec schedule) {
        return schedule != null
                && schedule.getStartTime() != null
                && schedule.getEndTime() != null
                && schedule.getEndTime().isBefore(schedule.getStartTime());
    }

    /**
     * VB-8J: the next instant at or after {@code now} at which the window is
     * open, or empty when no window is configured (always open).
     *
     * <p>Used to push {@code scheduledAt} forward rather than leaving an attempt
     * due-but-deferrable. That is what avoids the hot loop: requeueing every tick
     * costs two writes and a log line per queued attempt per tick, which for a
     * large campaign is unacceptable. Pushing the timestamp reuses the existing
     * due-selection mechanism and adds no new state.
     *
     * <p>The result is always strictly after {@code now}, so a caller can never
     * leave an attempt immediately due again.
     */
    public java.util.Optional<Instant> nextWindowOpen(ScheduleSpec schedule, Instant now) {
        if (schedule == null || !isScheduleWindowConfigured(schedule)) {
            return java.util.Optional.empty();
        }
        if (isWithinWindow(schedule, now)) {
            return java.util.Optional.of(now);
        }
        ZoneId zone = ZoneId.of(schedule.getTimezone().trim());
        Instant candidate;
        if (wrapsMidnight(schedule)) {
            // A wrapping window's next opening is always tonight's startTime:
            // it cannot be reached through the day-based adjustment below.
            candidate = now.atZone(zone).toLocalDate().atTime(schedule.getStartTime())
                    .atZone(zone).toInstant();
        } else {
            candidate = adjustToScheduleWindow(schedule, now);
        }
        if (!candidate.isAfter(now)) {
            // The adjustment can still land in the past for a schedule whose only
            // constraint is a start date in the future. Step a whole day so the
            // attempt is not re-selected on the very next tick.
            // It is re-evaluated then and stays queued and unfinished meanwhile,
            // which is the invariant that matters.
            candidate = now.atZone(zone).toLocalDate().plusDays(1)
                    .atStartOfDay(zone).toInstant();
        }
        return java.util.Optional.of(candidate);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
