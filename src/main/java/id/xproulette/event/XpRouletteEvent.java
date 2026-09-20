package id.xproulette.event;

/**
 * Adapter tipis agar XP Roulette terdaftar di EventManager.
 * XP Roulette punya siklus per pemain sendiri (RouletteManager), jadi adapter ini TIDAK
 * mengubah atau mengendalikan apa pun di sana: hanya melaporkan bahwa sistem itu hidup,
 * sebagai event latar (non-eksklusif) sehingga tidak pernah bertabrakan dengan event lain.
 */
public final class XpRouletteEvent implements GameEvent {

    public static final String ID = "xp-roulette";

    private volatile boolean running = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public EventType getType() {
        return EventType.XP_ROULETTE;
    }

    @Override
    public EventState getState() {
        return running ? EventState.ACTIVE : EventState.IDLE;
    }

    @Override
    public boolean isExclusive() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean canStart() {
        return false; // dikelola oleh siklusnya sendiri
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public void stop() {
        // sengaja kosong: XP Roulette dikendalikan lewat /xpr join|leave
    }

    @Override
    public void tick() {
        // siklus dijalankan oleh RouletteManager
    }

    @Override
    public void shutdown() {
        running = false;
    }
}
