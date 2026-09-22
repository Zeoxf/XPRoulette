package id.senzy.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Semua GUI Senzy adalah inventory chest biasa (tanpa resource pack, tanpa form khusus Java),
 * sehingga otomatis bisa dipakai pemain Bedrock lewat Geyser/Floodgate.
 */
public interface SenzyGui extends InventoryHolder {

    /** Dipanggil hanya untuk klik di inventory GUI (bukan inventory pemain). Event sudah di-cancel. */
    void onClick(InventoryClickEvent event);

    default void onClose(InventoryCloseEvent event) {}
}
