package com.northwater;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Dates {
    private static final DateTimeFormatter STORED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final DateTimeFormatter PRETTY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter PRETTY_YEAR =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter LONG =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MONTH_DAY =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US);

    private Dates() {}

    public static String nowStamp() {
        return LocalDateTime.now().withNano(0).format(STORED);
    }

    public static String pretty(String iso, LocalDate today) {
        LocalDate posted = LocalDate.parse(iso.substring(0, 10));
        DateTimeFormatter formatter = posted.getYear() == today.getYear() ? PRETTY : PRETTY_YEAR;
        return posted.format(formatter);
    }

    public static String longDate(LocalDate date) {
        return date.format(LONG);
    }

    public static String monthDayYear(String iso) {
        return LocalDate.parse(iso.substring(0, 10)).format(MONTH_DAY);
    }

    public static String monthYear(String iso) {
        return LocalDate.parse(iso.substring(0, 10)).format(MONTH_YEAR);
    }

    public static String timestamp(String stored) {
        return LocalDateTime.parse(stored, STORED).format(STAMP);
    }

    public static String greeting(LocalTime time) {
        int hour = time.getHour();
        if (hour < 12) {
            return "Good morning";
        }
        if (hour < 17) {
            return "Good afternoon";
        }
        return "Good evening";
    }
}
