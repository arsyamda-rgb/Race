package id.veyrion.race;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * VeyrionRace - intro + gacha ras tanpa inventory GUI.
 * UI memakai title/subtitle dan tombol klik di chat, jadi tidak ada judul inventory yang tembus.
 */
public final class VeyrionRace extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    public record RaceInfo(String id, String display, String trait, TextColor color, int weight) {}

    private static final class Session {
        final String token = UUID.randomUUID().toString().replace("-", "");
        BukkitTask task;
        boolean rolling;
        boolean wasInvulnerable;
    }

    private final Map<String, RaceInfo> races = new LinkedHashMap<>();
    private final Map<UUID, String> data = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Random random = new Random();
    private File dataFile;
    private YamlConfiguration dataCfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRaces();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("veyrionrace") != null) {
            getCommand("veyrionrace").setExecutor(this);
            getCommand("veyrionrace").setTabCompleter(this);
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VeyrionExpansion(this).register();
            getLogger().info("Placeholder %veyrion_race% terdaftar.");
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) cleanup(p);
        }
    }

    // ------------------------------------------------------------------ data

    private void loadRaces() {
        races.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("races");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) continue;
            TextColor color = TextColor.fromHexString(r.getString("color", "#FFFFFF"));
            if (color == null) color = NamedTextColor.WHITE;
            races.put(id.toLowerCase(Locale.ROOT), new RaceInfo(
                    id.toLowerCase(Locale.ROOT),
                    r.getString("display", id),
                    r.getString("trait", ""),
                    color,
                    Math.max(1, r.getInt("weight", 1))));
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        dataCfg = YamlConfiguration.loadConfiguration(dataFile);
        data.clear();
        for (String key : dataCfg.getKeys(false)) {
            try {
                data.put(UUID.fromString(key), dataCfg.getString(key, ""));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        try {
            dataCfg.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Gagal menyimpan data.yml: " + e.getMessage());
        }
    }

    public RaceInfo getRace(UUID uuid) {
        String id = data.get(uuid);
        return id == null ? null : races.get(id);
    }

    private void setRace(UUID uuid, String raceId) {
        data.put(uuid, raceId);
        dataCfg.set(uuid.toString(), raceId);
        saveData();
    }

    private void clearRace(UUID uuid) {
        data.remove(uuid);
        dataCfg.set(uuid.toString(), null);
        saveData();
    }

    // ------------------------------------------------------------------ join / login

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (data.containsKey(p.getUniqueId()) || sessions.containsKey(p.getUniqueId())) return;
        scheduleIntro(p);
    }

    private void scheduleIntro(Player p) {
        final long delay = getConfig().getLong("start-delay-ticks", 60L);
        new BukkitRunnable() {
            int waited = 0;

            @Override
            public void run() {
                if (!p.isOnline() || data.containsKey(p.getUniqueId()) || sessions.containsKey(p.getUniqueId())) {
                    cancel();
                    return;
                }
                if (!isAuthorized(p) && waited++ < 300) return; // tunggu login maks ~5 menit
                cancel();
                Bukkit.getScheduler().runTaskLater(VeyrionRace.this, () -> {
                    if (p.isOnline() && !data.containsKey(p.getUniqueId()) && !sessions.containsKey(p.getUniqueId())) {
                        startIntro(p);
                    }
                }, delay);
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    /** Cek LoginSecurity lewat reflection; kalau gagal dibaca dianggap sudah login. */
    private boolean isAuthorized(Player p) {
        if (!getConfig().getBoolean("wait-for-login", true)) return true;
        if (Bukkit.getPluginManager().getPlugin("LoginSecurity") == null) return true;
        try {
            Class<?> c = Class.forName("com.lenis0012.bukkit.loginsecurity.LoginSecurity");
            Object sm = c.getMethod("getSessionManager").invoke(null);
            Object session = null;
            for (var m : sm.getClass().getMethods()) {
                if (m.getName().equals("getPlayerSession") && m.getParameterCount() == 1) {
                    session = m.invoke(sm, p);
                    break;
                }
            }
            if (session == null) return true;
            return (boolean) session.getClass().getMethod("isAuthorized").invoke(session);
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ intro / menu / gacha

    private void startIntro(Player p) {
        Session s = new Session();
        s.wasInvulnerable = p.isInvulnerable();
        sessions.put(p.getUniqueId(), s);
        p.setInvulnerable(true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));

        showTitle(p, Component.text("VEYRION", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Roleplay", NamedTextColor.GOLD), 20, 80, 20);

        s.task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && sessions.get(p.getUniqueId()) == s) sendMenu(p, s);
        }, 100L);
    }

    private void sendMenu(Player p, Session s) {
        clearChat(p);
        p.sendMessage(Component.empty());
        p.sendMessage(Component.text("   VEYRION ", NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text("Roleplay", NamedTextColor.GOLD)));
        p.sendMessage(Component.empty());
        p.sendMessage(button("TENTUKAN RAS", NamedTextColor.LIGHT_PURPLE, "/veyrionrace ui roll " + s.token, null));
        p.sendMessage(button("KELUAR", NamedTextColor.GRAY, "/veyrionrace ui exit " + s.token, null));
        p.sendMessage(button("DISCORD", NamedTextColor.BLUE, null, getConfig().getString("discord", "")));
        p.sendMessage(Component.empty());
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    private Component button(String label, TextColor color, String command, String url) {
        Component c = Component.text("   \u25B6 " + label + "  ", color, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Klik di sini", NamedTextColor.GRAY)));
        if (command != null) c = c.clickEvent(ClickEvent.runCommand(command));
        if (url != null) c = c.clickEvent(ClickEvent.openUrl(url));
        return c;
    }

    private void clearChat(Player p) {
        for (int i = 0; i < 40; i++) p.sendMessage(Component.empty());
    }

    private void handleUi(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p) || a.length < 3) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.rolling || !s.token.equals(a[2])) return;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "roll" -> startRoll(p, s);
            case "exit" -> {
                String msg = getConfig().getString("exit-message", "Sampai jumpa!");
                p.kick(Component.text(msg));
            }
            default -> { }
        }
    }

    private void startRoll(Player p, Session s) {
        if (races.isEmpty()) return;
        s.rolling = true;
        clearChat(p);
        rollStep(p, s, 0, 24);
    }

    private void rollStep(Player p, Session s, int i, int total) {
        if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
        if (i >= total) {
            finishRoll(p, s);
            return;
        }
        List<RaceInfo> list = new ArrayList<>(races.values());
        RaceInfo shown = list.get(random.nextInt(list.size()));
        showTitle(p, Component.text(shown.display().toUpperCase(Locale.ROOT), shown.color(), TextDecoration.BOLD),
                Component.text("Menentukan ras...", NamedTextColor.LIGHT_PURPLE), 0, 8, 0);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f + (i / (float) total));
        s.task = Bukkit.getScheduler().runTaskLater(this, () -> rollStep(p, s, i + 1, total), 2L + i / 5);
    }

    private RaceInfo pickWeighted() {
        int sum = 0;
        for (RaceInfo r : races.values()) sum += r.weight();
        int roll = random.nextInt(Math.max(1, sum));
        for (RaceInfo r : races.values()) {
            roll -= r.weight();
            if (roll < 0) return r;
        }
        return races.values().iterator().next();
    }

    private void finishRoll(Player p, Session s) {
        RaceInfo result = pickWeighted();
        setRace(p.getUniqueId(), result.id()); // disimpan sekarang supaya tidak bisa reroll lewat relog

        showTitle(p, Component.text("SELAMAT KAMU MENJADI RAS", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text(result.display().toUpperCase(Locale.ROOT), result.color(), TextDecoration.BOLD), 10, 60, 10);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        s.task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
            showTitle(p,
                    Component.text("AYO TUNJUKKAN ", NamedTextColor.WHITE, TextDecoration.BOLD)
                            .append(Component.text(result.trait().toUpperCase(Locale.ROOT), result.color(), TextDecoration.BOLD)),
                    Component.text("DI DUNIA VEYRION", NamedTextColor.WHITE), 10, 60, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);

            s.task = Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
                cleanup(p); // baru sekarang pemain bisa bergerak
            }, 80L);
        }, 80L);
    }

    private void cleanup(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.setInvulnerable(s.wasInvulnerable);
    }

    private void showTitle(Player p, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    // ------------------------------------------------------------------ freeze selama sesi

    private boolean busy(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cleanup(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!busy(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent e) { if (busy(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onInteractEntity(PlayerInteractEntityEvent e) { if (busy(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { if (busy(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { if (busy(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onDrop(PlayerDropItemEvent e) { if (busy(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onChat(AsyncChatEvent e) { if (busy(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!busy(e.getPlayer())) return;
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (m.startsWith("/veyrionrace ui ") || m.startsWith("/ras ui ") || m.startsWith("/veyrionrace:veyrionrace ui ")) return;
        e.setCancelled(true);
    }

    // ------------------------------------------------------------------ perintah

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("ui")) {
            handleUi(sender, a);
            return true;
        }
        if (!sender.hasPermission("veyrionrace.admin")) {
            sender.sendMessage(Component.text("Kamu tidak punya izin.", NamedTextColor.RED));
            return true;
        }
        if (a.length == 0) {
            sender.sendMessage(Component.text("/" + label + " <get|set|reset|start|reload>", NamedTextColor.YELLOW));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                loadRaces();
                loadData();
                sender.sendMessage(Component.text("VeyrionRace di-reload.", NamedTextColor.GREEN));
            }
            case "get" -> {
                if (a.length < 2) return usage(sender, label + " get <player>");
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                RaceInfo r = getRace(op.getUniqueId());
                sender.sendMessage(Component.text(a[1] + ": " + (r == null ? "belum punya ras" : r.display()), NamedTextColor.YELLOW));
            }
            case "set" -> {
                if (a.length < 3) return usage(sender, label + " set <player> <" + String.join("|", races.keySet()) + ">");
                String id = a[2].toLowerCase(Locale.ROOT);
                if (!races.containsKey(id)) {
                    sender.sendMessage(Component.text("Ras tidak ada: " + id, NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                setRace(op.getUniqueId(), id);
                sender.sendMessage(Component.text(a[1] + " sekarang ras " + races.get(id).display(), NamedTextColor.GREEN));
            }
            case "reset" -> {
                if (a.length < 2) return usage(sender, label + " reset <player>");
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                clearRace(op.getUniqueId());
                sender.sendMessage(Component.text("Ras " + a[1] + " dihapus. Intro muncul lagi saat dia join.", NamedTextColor.GREEN));
            }
            case "start" -> {
                if (a.length < 2) return usage(sender, label + " start <player>");
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    sender.sendMessage(Component.text("Pemain tidak online.", NamedTextColor.RED));
                    return true;
                }
                if (sessions.containsKey(t.getUniqueId())) return true;
                startIntro(t);
            }
            default -> usage(sender, label + " <get|set|reset|start|reload>");
        }
        return true;
    }

    private boolean usage(CommandSender s, String text) {
        s.sendMessage(Component.text("/" + text, NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("veyrionrace.admin")) return out;
        if (a.length == 1) {
            for (String s : List.of("get", "set", "reset", "start", "reload")) if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
            for (String id : races.keySet()) if (id.startsWith(a[2].toLowerCase(Locale.ROOT))) out.add(id);
        }
        return out;
    }
}
