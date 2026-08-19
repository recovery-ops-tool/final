package com.recoverpro.server.service;

import com.recoverpro.server.repository.HolidayCalendarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallingHoursGuard {

    private final HolidayCalendarRepository holidayCalendarRepository;

    @Value("${app.cadence.calling-hours.start-hour:8}")
    private int startHour;

    @Value("${app.cadence.calling-hours.end-hour:19}")
    private int endHour;

    @Value("${app.cadence.calling-hours.timezone:Asia/Kolkata}")
    private String timezone;

    @Value("${app.cadence.calling-hours.allowed-days:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY}")
    private String allowedDaysCsv;

    @Value("${app.cadence.calling-hours.enforce:true}")
    private boolean enforce;

    public boolean isAllowedNow() {
        return isAllowed(ZonedDateTime.now(zoneId()));
    }

    public boolean isAllowedFor(UUID organizationId, ZonedDateTime when) {
        if (!isAllowed(when)) return false;
        if (organizationId == null) return true;
        try {
            boolean holiday = holidayCalendarRepository
                    .existsByOrganizationIdAndHolidayDateAndIsActiveTrue(organizationId, when.toLocalDate());
            if (holiday) {
                log.debug("Calling-hours block: holiday for org={} on {}", organizationId, when.toLocalDate());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Holiday lookup failed for org={}, denying outbound: {}", organizationId, e.getMessage());
            return false;
        }
    }

    public boolean isAllowed(ZonedDateTime when) {
        if (!enforce) return true;
        if (when == null) return false;
        ZonedDateTime local = when.withZoneSameInstant(zoneId());
        int hour = local.getHour();
        if (hour < startHour || hour >= endHour) return false;
        return allowedDays().contains(local.getDayOfWeek());
    }

    public String denialReason(UUID organizationId, ZonedDateTime when) {
        if (!enforce) return null;
        ZonedDateTime local = when.withZoneSameInstant(zoneId());
        int hour = local.getHour();
        if (hour < startHour || hour >= endHour) {
            return "outside calling hours " + startHour + ":00-" + endHour + ":00 " + timezone;
        }
        if (!allowedDays().contains(local.getDayOfWeek())) {
            return "day " + local.getDayOfWeek() + " not in allowed-days config";
        }
        if (organizationId != null && holidayCalendarRepository
                .existsByOrganizationIdAndHolidayDateAndIsActiveTrue(organizationId, local.toLocalDate())) {
            return "organization holiday on " + local.toLocalDate();
        }
        return null;
    }

    public Instant nextEligibleAt(UUID organizationId, ZonedDateTime startingAt) {
        ZonedDateTime z = startingAt == null
                ? ZonedDateTime.now(zoneId())
                : startingAt.withZoneSameInstant(zoneId());
        if (z.getHour() >= endHour) {
            z = z.plusDays(1).withHour(startHour).withMinute(0).withSecond(0).withNano(0);
        } else if (z.getHour() < startHour) {
            z = z.withHour(startHour).withMinute(0).withSecond(0).withNano(0);
        }
        Set<DayOfWeek> ok = allowedDays();
        for (int i = 0; i < 14; i++) {
            boolean dowOk = ok.contains(z.getDayOfWeek());
            boolean holiday = false;
            if (dowOk && organizationId != null) {
                try {
                    holiday = holidayCalendarRepository
                            .existsByOrganizationIdAndHolidayDateAndIsActiveTrue(organizationId, z.toLocalDate());
                } catch (Exception e) {
                    // SYSTEM 13 TASK 13.2: was silent and defaulted to holiday=false (permissive --
                    // treats an unverifiable day as safe to call), inconsistent with isAllowedFor's
                    // fail-closed default for this exact same lookup a few lines up. Fail closed
                    // here too: treat an unverifiable day as a holiday (skip it, try the next one)
                    // rather than risk scheduling a call on what might actually be a holiday.
                    log.warn("Holiday lookup failed for org={} date={}, treating as holiday (fail-closed): {}",
                            organizationId, z.toLocalDate(), e.getMessage());
                    holiday = true;
                }
            }
            if (dowOk && !holiday) return z.toInstant();
            z = z.plusDays(1).withHour(startHour).withMinute(0).withSecond(0).withNano(0);
        }
        return z.toInstant();
    }

    private ZoneId zoneId() {
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', falling back to Asia/Kolkata", timezone);
            return ZoneId.of("Asia/Kolkata");
        }
    }

    private Set<DayOfWeek> allowedDays() {
        EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(allowedDaysCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try { set.add(DayOfWeek.valueOf(s.toUpperCase())); }
                    catch (IllegalArgumentException ignored) {
                        log.warn("Unknown day '{}' in calling-hours config, ignoring", s);
                    }
                });
        if (set.isEmpty()) {
            set.addAll(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
        }
        return set;
    }
}
