package id.senzy.xpr;

import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Side effect yang muncul berkala selama boost aktif. Ditentukan lewat 5 tahap (stage):
 * makin tinggi progression, makin tinggi stage, makin ringan efeknya.
 */
public final class BoostSideEffect {

    /** @param amplifier level efek 1-based (1 = I, 3 = III) */
    public record Stage(int amplifier, long intervalMillis, int durationTicks) {}

    private final String id;
    private final PotionEffectType type;
    private final List<Stage> stages;

    public BoostSideEffect(String id, PotionEffectType type, List<Stage> stages) {
        this.id = id;
        this.type = type;
        this.stages = List.copyOf(stages);
    }

    public String id() {
        return id;
    }

    public PotionEffectType type() {
        return type;
    }

    public int stageCount() {
        return stages.size();
    }

    /** Stage 1-based; nilai di luar rentang dijepit. */
    public Stage stage(int stage) {
        int idx = Math.max(1, Math.min(stages.size(), stage)) - 1;
        return stages.get(idx);
    }
}
