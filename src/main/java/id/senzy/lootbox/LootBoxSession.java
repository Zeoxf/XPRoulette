package id.senzy.lootbox;

/** Satu putaran event LootBox (mis. 5 LootBox selama 1 jam). */
public final class LootBoxSession {

    private final String eventId;
    private final String sessionId;
    private final long startTime;
    private final long endTime;
    private final int amount;
    private boolean active = true;
    private String endReason = "";

    public LootBoxSession(String eventId, String sessionId, long startTime, long endTime, int amount) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.amount = amount;
    }

    public String eventId() {
        return eventId;
    }

    public String sessionId() {
        return sessionId;
    }

    public long startTime() {
        return startTime;
    }

    public long endTime() {
        return endTime;
    }

    /** Jumlah LootBox yang berhasil di-spawn pada session ini. */
    public int amount() {
        return amount;
    }

    public boolean active() {
        return active;
    }

    public String endReason() {
        return endReason;
    }

    public void end(String reason) {
        this.active = false;
        this.endReason = reason;
    }

    void restoreEnded(boolean active, String reason) {
        this.active = active;
        this.endReason = reason == null ? "" : reason;
    }

    public long remaining(long now) {
        return Math.max(0, endTime - now);
    }
}
