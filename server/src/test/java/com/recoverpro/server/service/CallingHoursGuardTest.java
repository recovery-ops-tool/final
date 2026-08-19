package com.recoverpro.server.service;

import com.recoverpro.server.repository.HolidayCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 13 TASK 13.2: {@code nextEligibleAt}'s holiday-lookup failure used to be a silent
 * fail-open (holiday=false, i.e. "safe to call") -- the exact opposite of {@link
 * #isAllowedFor_holidayLookupFails_failsClosedAndDenies} a few lines down, which already fails
 * closed for the identical lookup. Both are pinned here so a future edit can't quietly reintroduce
 * the inconsistency in just one of the two methods.
 */
@ExtendWith(MockitoExtension.class)
class CallingHoursGuardTest {

    @Mock private HolidayCalendarRepository holidayCalendarRepository;

    private CallingHoursGuard guard;

    @BeforeEach
    void setUp() {
        guard = new CallingHoursGuard(holidayCalendarRepository);
        ReflectionTestUtils.setField(guard, "startHour", 8);
        ReflectionTestUtils.setField(guard, "endHour", 19);
        ReflectionTestUtils.setField(guard, "timezone", "Asia/Kolkata");
        ReflectionTestUtils.setField(guard, "allowedDaysCsv", "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY");
        ReflectionTestUtils.setField(guard, "enforce", true);
    }

    @Test
    void nextEligibleAt_holidayLookupThrows_skipsTheDayRatherThanTrustingIt() {
        UUID orgId = UUID.randomUUID();
        // A Monday at 09:00 IST, comfortably inside calling hours -- the only thing standing
        // between "eligible now" and "must wait" is whether today is a holiday.
        ZonedDateTime monday9am = ZonedDateTime.of(2026, 8, 17, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
        assertThat(monday9am.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        LocalDate today = monday9am.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        when(holidayCalendarRepository.existsByOrganizationIdAndHolidayDateAndIsActiveTrue(eq(orgId), eq(today)))
                .thenThrow(new RuntimeException("DB unavailable"));
        when(holidayCalendarRepository.existsByOrganizationIdAndHolidayDateAndIsActiveTrue(eq(orgId), eq(tomorrow)))
                .thenReturn(false);

        Instant result = guard.nextEligibleAt(orgId, monday9am);

        // Fail-closed: today's unverifiable lookup is treated as a holiday, so the next eligible
        // slot is tomorrow at start-of-day, not today (which a fail-open default would have
        // returned, since 09:00 is already inside calling hours).
        ZonedDateTime resultZoned = result.atZone(ZoneId.of("Asia/Kolkata"));
        assertThat(resultZoned.toLocalDate()).isEqualTo(tomorrow);
        assertThat(resultZoned.getHour()).isEqualTo(8);
    }

    @Test
    void isAllowedFor_holidayLookupFails_failsClosedAndDenies() {
        UUID orgId = UUID.randomUUID();
        ZonedDateTime monday9am = ZonedDateTime.of(2026, 8, 17, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
        when(holidayCalendarRepository.existsByOrganizationIdAndHolidayDateAndIsActiveTrue(any(), any()))
                .thenThrow(new RuntimeException("DB unavailable"));

        assertThat(guard.isAllowedFor(orgId, monday9am)).isFalse();
    }
}
