package id.veyrion.race;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** %veyrion_race% -> Elf/Beast/Human/Dragon ("-" kalau belum punya), %veyrion_race_id% -> elf/beast/... */
public final class VeyrionExpansion extends PlaceholderExpansion {
    private final VeyrionRace plugin;

    public VeyrionExpansion(VeyrionRace plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "veyrion"; }
    @Override public @NotNull String getAuthor() { return "Veyrion"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "-";
        VeyrionRace.RaceInfo r = plugin.getRace(player.getUniqueId());
        switch (params.toLowerCase()) {
            case "race": return r == null ? "-" : r.display();
            case "race_id": return r == null ? "-" : r.id();
            default: return null;
        }
    }
}
