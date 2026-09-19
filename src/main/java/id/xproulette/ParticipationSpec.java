package id.xproulette;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lama ikut / keluar: selamanya, durasi waktu, atau jumlah round.
 * Contoh input: forever, 30m, 2h, 1d, 3r, "5 round", "2 jam".
 */
record ParticipationSpec(Kind kind, long seconds, int rounds) {

    enum Kind {FOREVER, TIME, ROUNDS}

    private static final Pattern FORMAT = Pattern.compile("^(\\d{1,9})([a-z]+)$");

    static ParticipationSpec forever() {
        return new ParticipationSpec(Kind.FOREVER, 0, 0);
    }

    /**
     * Membaca args[from..] (digabung tanpa spasi, jadi "2 jam" = "2jam").
     * Tanpa argumen = selamanya. Mengembalikan null jika tidak valid / melebihi batas.
     */
    static ParticipationSpec parse(String[] args, int from, int maxDays, int maxRounds) {
        if (args.length <= from) return forever();

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) sb.append(args[i]);
        String s = sb.toString().toLowerCase(Locale.ROOT);

        if (s.equals("forever") || s.equals("selamanya") || s.equals("permanen")
                || s.equals("permanent") || s.equals("perm")) {
            return forever();
        }

        Matcher m = FORMAT.matcher(s);
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        if (n <= 0) return null;
        String unit = m.group(2);

        switch (unit) {
            case "r", "round", "rounds", "putaran", "siklus" -> {
                if (n > maxRounds) return null;
                return new ParticipationSpec(Kind.ROUNDS, 0, (int) n);
            }
            default -> {
            }
        }

        long mult = switch (unit) {
            case "s", "sec", "second", "seconds", "detik" -> 1L;
            case "m", "min", "minute", "minutes", "menit" -> 60L;
            case "h", "hr", "hour", "hours", "jam" -> 3600L;
            case "d", "day", "days", "hari" -> 86400L;
            default -> 0L;
        };
        if (mult == 0L) return null;

        long seconds = n * mult;
        if (seconds > (long) maxDays * 86400L) return null;
        return new ParticipationSpec(Kind.TIME, seconds, 0);
    }
}
