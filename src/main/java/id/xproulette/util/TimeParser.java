package id.xproulette.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Membaca dan menampilkan durasi: 1h, 30m, 90s, 1h30m, 2d, atau angka polos (= detik). */
public final class TimeParser {

    private static final Pattern PART = Pattern.compile("(\\d{1,9})([dhms])");

    private TimeParser() {
    }

    public static long parseSeconds(String text, long defaultSeconds) {
        if (text == null) return defaultSeconds;
        String s = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return defaultSeconds;

        if (s.matches("\\d{1,12}")) return Long.parseLong(s);

        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != end) return defaultSeconds; // ada karakter asing di tengah
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d" -> total += n * 86400L;
                case "h" -> total += n * 3600L;
                case "m" -> total += n * 60L;
                default -> total += n;
            }
            end = m.end();
            any = true;
        }
        if (!any || end != s.length()) return defaultSeconds;
        return total;
    }

    /** 3735 -> "1h 2m 15s", 900 -> "15m", 0 -> "0s". */
    public static String format(long seconds) {
        seconds = Math.max(0, seconds);
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
