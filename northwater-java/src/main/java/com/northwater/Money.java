package com.northwater;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Money {
    public static final long MAX_CENTS = 100_000_000L;
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");

    private Money() {}

    public static String format(long cents) {
        return format(cents, false);
    }

    public static String format(long cents, boolean signed) {
        long absolute = Math.abs(cents);
        String body = "$" + String.format(Locale.US, "%,d", absolute / 100) + "."
                + String.format(Locale.US, "%02d", absolute % 100);
        if (!signed || cents == 0) {
            return body;
        }
        return cents > 0 ? "+" + body : "\u2212" + body;
    }

    public static String dollars(long cents) {
        return String.format(Locale.US, "%,d", Math.abs(cents) / 100);
    }

    public static String centsPart(long cents) {
        return String.format(Locale.US, "%02d", Math.abs(cents) % 100);
    }

    public static long parse(String raw) {
        if (raw == null) {
            throw new FormException("Enter a dollar amount like 25.00.");
        }
        String text = raw.strip().replace("$", "").replace(",", "").replace(" ", "");
        if (!AMOUNT.matcher(text).matches()) {
            throw new FormException("Enter a dollar amount like 25.00.");
        }
        String whole;
        String frac;
        int dot = text.indexOf('.');
        if (dot >= 0) {
            whole = text.substring(0, dot);
            frac = (text.substring(dot + 1) + "00").substring(0, 2);
        } else {
            whole = text;
            frac = "00";
        }
        long cents = Long.parseLong(whole) * 100 + Long.parseLong(frac);
        if (cents <= 0) {
            throw new FormException("Enter an amount greater than zero.");
        }
        if (cents > MAX_CENTS) {
            throw new FormException("Enter an amount up to $1,000,000.00.");
        }
        return cents;
    }

    public static String mask(String number) {
        return "ending " + number.substring(number.length() - 4);
    }
}
