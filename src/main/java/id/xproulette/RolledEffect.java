package id.xproulette;

/**
 * Satu efek hasil putaran roulette.
 *
 * @param key   ID efek vanilla (mis. "speed", "poison")
 * @param level level efek (1 = I, 2 = II, dst). Amplifier di Minecraft = level - 1
 * @param risk  true jika efek ini termasuk RISIKO (efek buruk)
 */
record RolledEffect(String key, int level, boolean risk) {
}
