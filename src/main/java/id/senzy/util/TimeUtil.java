package id.senzy.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing & formatting durasi ("10m", "1h30m", "45s", "2d"). */
public final class TimeUtil {

    private static final Pattern TOKEN = Pattern.compile("(\\d{1,9})(ms|s|m|h|d)");

    private TimeUtil() {}

    /** Membaca durasi. Angka polos dianggap detik. Mengembalikan null jika format salah. */
    public static Long parse(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return null;
        if (s.chars().allMatch(Character::isDigit)) {
            if (s.length() > 9) return null;
            return Long.parseLong(s) * 1000L;
        }
        Matcher m = TOKEN.matcher(s);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) return null;
            total += Long.parseLong(m.group(1)) * unit(m.group(2));
            consumed = m.end();
        }
        return (consumed == s.length() && consumed > 0) ? total : null;
    }

    public static long parse(String input, long defaultMillis) {
        Long v = parse(input);
        return v == null ? defaultMillis : v;
    }

    private static long unit(String u) {
        return switch (u) {
            case "ms" -> 1L;
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            default -> 86_400_000L; // d
        };
    }

    /** Format ringkas maksimal 2 satuan: "1h 5m", "39m", "12m 30s", "45s". */
    public static String format(long millis) {
        if (millis <= 0) return "0s";
        long totalSec = (millis + 999) / 1000;
        long d = totalSec / 86_400;
        long h = (totalSec % 86_400) / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        StringBuilder sb = new StringBuilder();
        int parts = 0;
        if (d > 0) { sb.append(d).append("d "); parts++; }
        if (h > 0 && parts < 2) { sb.append(h).append("h "); parts++; }
        if (m > 0 && parts < 2) { sb.append(m).append("m "); parts++; }
        if (s > 0 && parts < 2) { sb.append(s).append("s "); parts++; }
        return sb.toString().trim();
    }
}
