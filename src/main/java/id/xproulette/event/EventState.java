package id.xproulette.event;

/** IDLE -> STARTING -> ACTIVE -> ENDING -> COOLDOWN -> IDLE */
public enum EventState {
    IDLE,
    STARTING,
    ACTIVE,
    ENDING,
    COOLDOWN;

    /** Sedang berjalan (belum selesai). */
    public boolean isRunning() {
        return this == STARTING || this == ACTIVE || this == ENDING;
    }
}
