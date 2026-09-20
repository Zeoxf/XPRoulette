package id.xproulette.lootbox;

import id.xproulette.util.Lookup;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/** Efek visual + suara yang dibaca dari config (tidak ada yang di-hardcode). */
public final class EffectSpec {

    private final Particle particle;
    private final Sound sound;
    private final int count;
    private final float volume;
    private final float pitch;
    private final boolean global;

    private EffectSpec(Particle particle, Sound sound, int count, float volume, float pitch, boolean global) {
        this.particle = particle;
        this.sound = sound;
        this.count = count;
        this.volume = volume;
        this.pitch = pitch;
        this.global = global;
    }

    /** Membaca satu bagian efek. Nama yang salah hanya memberi warning; efek itu dilewati. */
    public static EffectSpec read(ConfigurationSection sec, Logger log, String label) {
        if (sec == null) return new EffectSpec(null, null, 0, 1f, 1f, false);

        String pName = sec.getString("particle", "");
        String sName = sec.getString("sound", "");
        Particle particle = Lookup.constant(Particle.class, pName);
        Sound sound = Lookup.constant(Sound.class, sName);
        if (!pName.isBlank() && particle == null) log.warning("effects." + label + ": partikel tidak dikenal '" + pName + "'");
        if (!sName.isBlank() && sound == null) log.warning("effects." + label + ": sound tidak dikenal '" + sName + "'");

        return new EffectSpec(particle, sound,
                Math.max(0, Math.min(500, sec.getInt("count", 30))),
                (float) sec.getDouble("volume", 1.0),
                (float) sec.getDouble("pitch", 1.0),
                sec.getBoolean("global-sound", false));
    }

    public Particle particle() {
        return particle;
    }

    public int count() {
        return count;
    }

    /** Memainkan efek di lokasi. Tidak pernah melempar exception. */
    public void play(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        if (particle != null && count > 0) {
            try {
                w.spawnParticle(particle, loc, count, 0.4, 0.6, 0.4, 0.05);
            } catch (RuntimeException ignored) {
                // partikel yang butuh data tambahan (mis. DUST) tidak didukung -> dilewati
            }
        }
        if (sound != null) {
            if (global) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), sound, volume, pitch);
                }
            } else {
                w.playSound(loc, sound, volume, pitch);
            }
        }
    }
}
