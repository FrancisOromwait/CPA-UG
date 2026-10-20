package com.estates.sacco.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of a mobile money "you have received" message (SMS or WhatsApp, MTN or Airtel) and pulls out the amount, the
 * sender's number and name, the transaction ID and the date. It only reads; the person recording the deposit checks the result.
 * Anything it cannot find is left null.
 */
public final class MobileMoneyMessageParser {
    private MobileMoneyMessageParser() {}

    public record Parsed(BigDecimal amount, String senderPhone, String senderName, String transactionId, LocalDate date, String network) {}

    private static final String NUMBER = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";
    private static final Pattern AMOUNT_BEFORE = Pattern.compile("(?i)(?:UGX|USH|UGS|UShs)\\.?\\s*" + NUMBER);
    private static final Pattern AMOUNT_AFTER = Pattern.compile("(?i)" + NUMBER + "\\s*(?:UGX|USH|UGS|UShs)\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+?256|0)(7[0-9]{8})(?![0-9])");
    private static final Pattern NAME = Pattern.compile("(?i)\\bfrom\\s+([A-Za-z][A-Za-z .'-]{1,60}?)(?=\\s*(?:\\(|\\+?[0-9]|\\.|,|\\bon\\b|\\bat\\b|\\bTransaction\\b|\\bTID\\b|\\bRef\\b|$))");
    private static final Pattern TID = Pattern.compile("(?i)(?:transaction\\s*id|trans\\s*id|txn\\s*id|tid|reference|ref)\\s*[:#.]?\\s*([A-Z0-9][A-Z0-9._-]{5,})");
    private static final Pattern ISO_DATE = Pattern.compile("(20[0-9]{2})-([0-9]{2})-([0-9]{2})");
    private static final Pattern DMY_DATE = Pattern.compile("(?<![0-9])([0-9]{1,2})/([0-9]{1,2})/(20[0-9]{2})(?![0-9])");

    public static Parsed parse(String text) {
        String t = text == null ? "" : text.replace(' ', ' ').trim();
        // the amount received comes after "received" (a balance quoted later in the message must not be taken)
        int from = Math.max(0, indexOfIgnoreCase(t, "received"));
        BigDecimal amount = firstAmount(t.substring(from));
        if (amount == null) amount = firstAmount(t);

        int fromWord = indexOfIgnoreCase(t, " from ");
        Matcher p = PHONE.matcher(fromWord >= 0 ? t.substring(fromWord) : t);
        String phone = p.find() ? "0" + p.group(1) : null;
        if (phone == null && fromWord >= 0) {
            Matcher any = PHONE.matcher(t);
            if (any.find()) phone = "0" + any.group(1);
        }
        Matcher nm = NAME.matcher(t);
        String name = nm.find() ? nm.group(1).trim().replaceAll("\\s+", " ") : null;
        Matcher tid = TID.matcher(t);
        String transactionId = tid.find() ? tid.group(1).replaceAll("[.]+$", "") : null;

        String lower = t.toLowerCase();
        // Airtel Money transaction IDs look like MP240512.1234.A12345; an MTN message usually says MTN or MoMo
        boolean airtelId = transactionId != null && transactionId.matches("(?i)MP[0-9]{6}\\.[0-9]{4}\\.[A-Z0-9]+");
        String network = lower.contains("airtel") || airtelId ? "Airtel" : (lower.contains("mtn") || lower.contains("momo")) ? "MTN" : null;
        return new Parsed(amount, phone, name, transactionId, date(t), network);
    }

    private static BigDecimal firstAmount(String s) {
        Matcher a = AMOUNT_BEFORE.matcher(s);
        Matcher b = AMOUNT_AFTER.matcher(s);
        boolean hasA = a.find(), hasB = b.find();
        Matcher use = hasA && (!hasB || a.start() <= b.start()) ? a : hasB ? b : null;
        if (use == null) return null;
        try {
            return new BigDecimal(use.group(1).replace(",", "")).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String t) {
        try {
            Matcher iso = ISO_DATE.matcher(t);
            if (iso.find()) return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            Matcher dmy = DMY_DATE.matcher(t);
            if (dmy.find()) return LocalDate.of(Integer.parseInt(dmy.group(3)), Integer.parseInt(dmy.group(2)), Integer.parseInt(dmy.group(1)));
        } catch (java.time.DateTimeException e) {
            // not a real date: leave it out
        }
        return null;
    }

    private static int indexOfIgnoreCase(String s, String what) {
        return s.toLowerCase().indexOf(what.toLowerCase());
    }
}
