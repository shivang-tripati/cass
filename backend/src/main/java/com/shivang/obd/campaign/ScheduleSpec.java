package com.shivang.obd.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Campaign-owned schedule configuration value object. Timezone is an
 * explicit IANA identifier; the execution engine interprets the window
 * in this zone. All fields are optional until the campaign is configured
 * (activation requires a complete schedule).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class ScheduleSpec {

    @Column(name = "schedule_start_date")
    private LocalDate startDate;

    @Column(name = "schedule_end_date")
    private LocalDate endDate;

    @Column(name = "daily_start_time")
    private LocalTime startTime;

    @Column(name = "daily_end_time")
    private LocalTime endTime;

    /** IANA timezone identifier, e.g. "Asia/Kolkata". */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /**
     * Recurring eligibility filter as type-safe day names; null or empty
     * means unrestricted. Interpreted in the campaign timezone.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_days_of_week")
    private Set<DayOfWeek> allowedDaysOfWeek;

    /**
     * Holiday/blackout calendar reference into the future
     * Compliance/HolidayCalendar module. Plain UUID by design — no FK,
     * no local holiday data; the owning module resolves it later.
     */
    @Column(name = "holiday_calendar_id")
    private java.util.UUID holidayCalendarId;
}
