package id.senzy.event;

import id.senzy.SenzyPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Mengelola daur hidup semua modul Senzy. Satu modul yang error tidak menjatuhkan modul lain. */
public final class SenzyEventManager {

    private final SenzyPlugin plugin;
    private final Map<String, SenzyModule> modules = new LinkedHashMap<>();

    public SenzyEventManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(SenzyModule module) {
        modules.put(module.id(), module);
    }

    public Collection<SenzyModule> modules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public void enableAll() {
        for (SenzyModule m : modules.values()) {
            try {
                m.enable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Gagal menyalakan modul '" + m.id() + "'", e);
            }
        }
    }

    public void disableAll() {
        List<SenzyModule> reversed = new ArrayList<>(modules.values());
        Collections.reverse(reversed);
        for (SenzyModule m : reversed) {
            try {
                m.disable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Gagal mematikan modul '" + m.id() + "'", e);
            }
        }
    }

    public void reloadAll() {
        for (SenzyModule m : modules.values()) {
            try {
                m.reload();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Gagal reload modul '" + m.id() + "'", e);
            }
        }
    }
}
