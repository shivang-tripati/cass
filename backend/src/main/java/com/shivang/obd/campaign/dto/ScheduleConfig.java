package com.shivang.obd.campaign.dto;

import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Schedule window configuration. All fields optional while the campaign
 * is a draft; when provided, combinations are validated in the service
 * layer (time order, valid IANA timezone, timezone required
 * once any window field is set). The timezone must be an explicit IANA
 * identifier — never the server default.
 *
 * <p>There is deliberately no end date: a campaign has a start and then
 * remains eligible across future calling windows until its work is exhausted.
 */
public record ScheduleConfig(
    LocalDate startDate,
    LocalTime startTime,
    LocalTime endTime,
    @Size(max = 64) String timezone,
    Set<DayOfWeek> allowedDaysOfWeek,
    UUID holidayCalendarId
) {
}
