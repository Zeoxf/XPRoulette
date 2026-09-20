package id.xproulette.event;

/** Hasil pengecekan apakah sebuah event boleh dimulai. */
public enum StartCheck {
    OK,
    NOT_REGISTERED,
    DISABLED,
    ALREADY_RUNNING,
    COOLDOWN,
    BLOCKED_BY_OTHER_EVENT,
    NOT_READY
}
