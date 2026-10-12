package dev.runes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class RunesPlugin extends JavaPlugin implements Listener, TabExecutor {

    // ---------------------------------------------------------------- runes

    public enum Rune {
        STRENGTH("Strength Rune", NamedTextColor.RED, Material.ECHO_SHARD,
                "Slay {n} hostile mobs in a row without taking ANY damage in between",
                "Shift + Right-click: start charging",
                "Your hits deal NO damage but build stacks",
                "Right-click: release - your next hit deals bonus damage"),
        SPEED("Speed Rune", NamedTextColor.YELLOW, Material.GLOWSTONE_DUST,
                "Slay {n} hostile mobs while sprinting",
                "Shift + Right-click: build MOMENTUM",
                "Sprint to build stacks",
                "Right-click: dash forward + speed buff scaled by stacks"),
        WATER("Water Rune", NamedTextColor.AQUA, Material.PRISMARINE_CRYSTALS,
                "Slay {n} hostile mobs while in water or standing in the rain",
                "Shift + Right-click: raise the TIDE",
                "You take less damage and store it",
                "Right-click: tidal wave deals the stored damage and heals you"),
        WEATHER("Weather Rune", NamedTextColor.LIGHT_PURPLE, Material.AMETHYST_SHARD,
                "Slay {n} hostile mobs during a thunderstorm",
                "Shift + Right-click: gather a STORM (you are slowed)",
                "Stacks build over time, faster in rain",
                "Right-click: lightning rains on enemies you look at",
                "Right-click (idle): cycle the weather"),
        FIRE("Fire Rune", NamedTextColor.GOLD, Material.BLAZE_POWDER,
                "Slay {n} hostile mobs while on fire or while in the Nether",
                "Shift + Right-click: ignite EMBER MARKS",
                "Your hits mark enemies with embers",
                "Right-click: every marked enemy detonates"),
        EARTH("Earth Rune", NamedTextColor.DARK_GREEN, Material.CLAY_BALL,
                "Slay {n} hostile mobs deep underground (below Y=40, no sky above)",
                "Shift + Right-click: awaken BEDROCK",
                "Mine stone, ores and dirt to build stacks",
                "Right-click: shield, resistance and a shockwave"),
        AIR("Air Rune", NamedTextColor.WHITE, Material.FEATHER,
                "Slay {n} hostile mobs while high in the air (4+ blocks above ground, or gliding)",
                "Shift + Right-click: gather the GALE",
                "Jump to build stacks",
                "Right-click: launch into the sky + gust blasts enemies away");

        final String display;
        final TextColor color;
        final Material material;
        final String trialText;
        final String[] lore;

        Rune(String display, TextColor color, Material material, String trialText, String... lore) {
            this.display = display;
            this.color = color;
            this.material = material;
            this.trialText = trialText;
            this.lore = lore;
        }

        String id() { return name().toLowerCase(Locale.ROOT); }

        static Rune fromId(String id) {
            if (id == null) return null;
            for (Rune r : values()) if (r.id().equalsIgnoreCase(id)) return r;
            return null;
        }
    }

    // ---------------------------------------------------------------- state

    private enum Phase { IDLE, CHARGING, READY }

    private static final class State {
        Rune active = null;
        Phase phase = Phase.IDLE;
        int stacks = 0;
        double pool = 0;          // water: stored damage | speed: distance sprinted
        long startedAt = 0;
        long lastGain = 0;
        long lastActive = 0;
        long readyUntil = 0;      // strength
        long lastClick = 0;
        Location lastLoc = null;  // speed
        int jumpSeen = 0;         // air
        final Map<UUID, Integer> marks = new HashMap<>(); // fire
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Set<Location> placedBlocks = new HashSet<>(); // stops place-and-break farming for earth
    private NamespacedKey runeKey, scrollKey, trialKey, progressKey, spawnerKey;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();
    private boolean internalDamage = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runeKey = new NamespacedKey(this, "rune");
        scrollKey = new NamespacedKey(this, "scroll");
        trialKey = new NamespacedKey(this, "trial");
        progressKey = new NamespacedKey(this, "trial_progress");
        spawnerKey = new NamespacedKey(this, "from_spawner");
        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("rune");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 5L, 5L);
    }

    // --------------------------------------------------------------- config

    private double d(Rune r, String key, double def) {
        return getConfig().getDouble("runes." + r.id() + "." + key, def);
    }

    private int i(Rune r, String key, int def) {
        return getConfig().getInt("runes." + r.id() + "." + key, def);
    }

    private double defaultMaxCharge(Rune r) {
        return switch (r) {
            case SPEED -> 20;
            case WATER -> 12;
            case WEATHER -> 15;
            case FIRE -> 14;
            case EARTH -> 60;
            case AIR -> 15;
            default -> 0;
        };
    }

    // ----------------------------------------------------------------- item

    private ItemStack createRune(Rune rune) {
        ItemStack item = new ItemStack(rune.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(rune.display, rune.color)
                .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Hold in your offhand", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (String line : rune.lore) {
            lore.add(Component.text(line, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(runeKey, PersistentDataType.STRING, rune.id());
        item.setItemMeta(meta);
        return item;
    }

    private Rune runeOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        return Rune.fromId(item.getItemMeta().getPersistentDataContainer().get(runeKey, PersistentDataType.STRING));
    }

    // ------------------------------------------------------------ utilities

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private void bar(Player p, String text, TextColor color) {
        p.sendActionBar(Component.text(text, color));
    }

    private long remainingMs(Player p, String key) {
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0;
        Long t = m.get(key);
        return t == null ? 0 : Math.max(0, t - System.currentTimeMillis());
    }

    private void startCd(Player p, String key, double seconds) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .put(key, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    private boolean ready(Player p, String key) {
        long left = remainingMs(p, key);
        if (left <= 0) return true;
        bar(p, "On cooldown: " + ((left + 999) / 1000) + "s", NamedTextColor.GRAY);
        return false;
    }

    private boolean valid(Player caster, LivingEntity e) {
        if (e.equals(caster) || e.isDead() || e instanceof ArmorStand) return false;
        if (e instanceof Player other && other.getGameMode() == GameMode.SPECTATOR) return false;
        if (e instanceof Tameable t && t.getOwnerUniqueId() != null
                && t.getOwnerUniqueId().equals(caster.getUniqueId())) return false;
        return true;
    }

    private List<LivingEntity> nearby(Player p, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof LivingEntity le && valid(p, le)
                    && le.getLocation().distanceSquared(p.getLocation()) <= r2) out.add(le);
        }
        return out;
    }

    private void hurt(Player p, LivingEntity target, double dmg) {
        if (dmg <= 0) return;
        internalDamage = true;
        try {
            target.damage(dmg, p);
        } finally {
            internalDamage = false;
        }
    }

    private void effect(Player p, PotionEffectType type, int ticks, int amp) {
        p.addPotionEffect(new PotionEffect(type, ticks, amp, false, true, true));
    }

    private Vector flatAway(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() < 0.0001) v = new Vector(0, 0, 1);
        return v.normalize();
    }

    // -------------------------------------------------- charge life-cycle

    private void begin(Player p, State s, Rune r, long now) {
        s.active = r;
        s.phase = Phase.CHARGING;
        s.stacks = 0;
        s.pool = 0;
        s.startedAt = now;
        s.lastGain = now;
        s.lastActive = now;
        s.marks.clear();
        s.lastLoc = p.getLocation().clone();
        s.jumpSeen = p.getStatistic(Statistic.JUMP);

        String msg = switch (r) {
            case STRENGTH -> "Strength charging - your hits deal no damage, they build stacks!";
            case SPEED -> "Momentum building - SPRINT!";
            case WATER -> "Tide rising - you take less damage and store it!";
            case WEATHER -> "Gathering the storm... you are slowed.";
            case FIRE -> "Embers lit - hit enemies to mark them!";
            case EARTH -> "Bedrock awakening - mine stone, ores and dirt!";
            case AIR -> "Gathering wind - JUMP to build the gale!";
        };
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.6f);
        bar(p, msg, r.color);
    }

    /** Ends whatever is active. cooldownFraction: 1 = full cooldown, 0 = none. */
    private void end(Player p, State s, double cooldownFraction) {
        Rune r = s.active;
        if (r == Rune.WEATHER) p.removePotionEffect(PotionEffectType.SLOWNESS);
        s.active = null;
        s.phase = Phase.IDLE;
        s.stacks = 0;
        s.pool = 0;
        s.marks.clear();
        s.lastLoc = null;
        if (r != null && cooldownFraction > 0) {
            startCd(p, r.id(), d(r, "cooldown-seconds", 30) * cooldownFraction);
        }
    }

    // ------------------------------------------------------------- activate

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        Rune rune = runeOf(p.getInventory().getItemInOffHand());
        if (rune == null || !p.hasPermission("runes.use")) return;
        if (scrollOf(p.getInventory().getItemInMainHand()) != null) return;

        boolean sneaking = p.isSneaking();
        if (!sneaking && a == Action.RIGHT_CLICK_BLOCK
                && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) return;

        State s = state(p);
        long now = System.currentTimeMillis();
        if (now - s.lastClick < 250) return;
        s.lastClick = now;

        // Stale charge from a different rune: drop it
        if (s.phase == Phase.CHARGING && s.active != rune) end(p, s, 0);

        if (sneaking) {
            switch (s.phase) {
                case IDLE -> {
                    if (!ready(p, rune.id())) return;
                    begin(p, s, rune, now);
                }
                case CHARGING -> {
                    end(p, s, 0);
                    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 1.4f);
                    bar(p, "Charge cancelled.", NamedTextColor.GRAY);
                }
                case READY -> bar(p, "Charged hit is ready - land it!", NamedTextColor.GOLD);
            }
            return;
        }

        if (s.phase == Phase.CHARGING && s.active == rune) {
            release(p, s, rune, now);
        } else if (s.phase == Phase.IDLE && rune == Rune.WEATHER) {
            changeWeather(p);
        }
    }

    private void release(Player p, State s, Rune r, long now) {
        switch (r) {
            case STRENGTH -> releaseStrength(p, s, now);
            case SPEED -> releaseSpeed(p, s);
            case WATER -> releaseWater(p, s);
            case WEATHER -> releaseWeather(p, s);
            case FIRE -> releaseFire(p, s);
            case EARTH -> releaseEarth(p, s);
            case AIR -> releaseAir(p, s);
        }
    }

    // ------------------------------------------------------------- STRENGTH

    private void releaseStrength(Player p, State s, long now) {
        int min = i(Rune.STRENGTH, "min-stacks-to-release", 3);
        if (s.stacks < min) {
            bar(p, "Need at least " + min + " stacks (" + s.stacks + ")", NamedTextColor.GRAY);
            return;
        }
        s.phase = Phase.READY;
        s.readyUntil = now + (long) (d(Rune.STRENGTH, "released-hit-window-seconds", 10) * 1000);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.6f);
        p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.2);
        bar(p, "RELEASED x" + s.stacks + " - your next hit is charged!", NamedTextColor.GOLD);
    }

    // ---------------------------------------------------------------- SPEED

    private void releaseSpeed(Player p, State s) {
        int min = i(Rune.SPEED, "min-stacks", 2);
        int stacks = s.stacks;
        if (stacks < min) {
            bar(p, "Need at least " + min + " momentum stacks (" + stacks + ")", NamedTextColor.GRAY);
            return;
        }
        Vector dir = p.getLocation().getDirection();
        dir.setY(0);
        dir = dir.lengthSquared() < 0.0001 ? new Vector(0, 0, 0) : dir.normalize();
        double power = Math.min(d(Rune.SPEED, "max-dash-power", 2.2),
                d(Rune.SPEED, "dash-base", 0.7) + d(Rune.SPEED, "dash-per-stack", 0.14) * stacks);
        Vector v = dir.multiply(power);
        v.setY(0.25);
        p.setVelocity(v);

        int amp = stacks >= i(Rune.SPEED, "speed-iii-stacks", 7) ? 2
                : stacks >= i(Rune.SPEED, "speed-ii-stacks", 4) ? 1 : 0;
        double secs = d(Rune.SPEED, "speed-buff-base-seconds", 3) + d(Rune.SPEED, "speed-buff-seconds-per-stack", 1.0) * stacks;
        effect(p, PotionEffectType.SPEED, (int) (secs * 20), amp);

        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.3, 0), 40, 0.4, 0.2, 0.4, 0.1);
        p.playSound(p.getLocation(), Sound.ENTITY_HORSE_GALLOP, 1f, 1.4f);
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1.2f);
        bar(p, "MOMENTUM x" + stacks + " - Speed " + (amp + 1 == 1 ? "I" : amp + 1 == 2 ? "II" : "III") + " for " + String.format("%.0f", secs) + "s!", NamedTextColor.YELLOW);
        end(p, s, 1.0);
    }

    // ---------------------------------------------------------------- WATER

    private void releaseWater(Player p, State s) {
        double min = d(Rune.WATER, "min-pool", 3);
        double pool = s.pool;
        if (pool < min) {
            bar(p, "Tide too low - take some hits first (" + String.format("%.1f", pool) + "/" + String.format("%.1f", min) + ")", NamedTextColor.GRAY);
            return;
        }
        double radius = Math.min(d(Rune.WATER, "wave-radius-max", 7),
                d(Rune.WATER, "wave-radius-base", 4) + pool * d(Rune.WATER, "wave-radius-per-pool", 0.2));
        double kb = Math.min(d(Rune.WATER, "knockback-max", 1.8),
                d(Rune.WATER, "knockback-base", 0.8) + pool * d(Rune.WATER, "knockback-per-pool", 0.06));
        double dmg = Math.min(d(Rune.WATER, "max-wave-damage", 12), pool * d(Rune.WATER, "wave-damage-multiplier", 1.0));

        int hit = 0;
        for (LivingEntity t : nearby(p, radius)) {
            Vector push = flatAway(p.getLocation(), t.getLocation()).multiply(kb);
            push.setY(0.4);
            hurt(p, t, dmg);
            t.setVelocity(push);
            hit++;
        }
        double heal = pool * d(Rune.WATER, "heal-fraction", 0.3);
        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20;
        p.setHealth(Math.min(maxHp, p.getHealth() + heal));
        p.setFireTicks(0);
        effect(p, PotionEffectType.WATER_BREATHING, (int) (d(Rune.WATER, "water-breathing-seconds", 15) * 20), 0);

        p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(0, 1, 0), 200, radius / 2, 0.7, radius / 2, 0.15);
        p.getWorld().spawnParticle(Particle.BUBBLE_POP, p.getLocation().add(0, 1, 0), 60, radius / 2, 0.7, radius / 2, 0.05);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.8f);
        bar(p, "TIDAL WAVE! " + String.format("%.1f", dmg) + " dmg x" + hit + ", healed " + String.format("%.1f", heal), NamedTextColor.AQUA);
        end(p, s, 1.0);
    }

    // -------------------------------------------------------------- WEATHER

    private void strike(Player p, Location at, double radius, double dmg) {
        World w = at.getWorld();
        w.strikeLightningEffect(at); // visual + sound only: no fire, no block damage
        w.spawnParticle(Particle.ELECTRIC_SPARK, at, 40, 0.5, 0.8, 0.5, 0.2);
        for (Entity e : w.getNearbyEntities(at, radius, radius, radius)) {
            if (e instanceof LivingEntity le && valid(p, le)) hurt(p, le, dmg);
        }
    }

    private void releaseWeather(Player p, State s) {
        int min = i(Rune.WEATHER, "min-stacks", 2);
        if (s.stacks < min) {
            bar(p, "Need at least " + min + " storm stacks (" + s.stacks + ")", NamedTextColor.GRAY);
            return;
        }
        double range = d(Rune.WEATHER, "bolt-range", 25);
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();

        List<LivingEntity> cands = new ArrayList<>();
        for (Entity en : p.getNearbyEntities(range, range, range)) {
            if (en instanceof LivingEntity le && valid(p, le) && p.hasLineOfSight(le)) {
                Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
                if (to.lengthSquared() > range * range) continue;
                if (to.normalize().dot(look) > 0.75) cands.add(le);
            }
        }
        cands.sort(Comparator.comparingDouble(le ->
                -le.getEyeLocation().toVector().subtract(eye.toVector()).normalize().dot(look)));

        Location fallback = null;
        if (cands.isEmpty()) {
            Block b = p.getTargetBlockExact((int) range);
            if (b == null) {
                bar(p, "Aim at something!", NamedTextColor.GRAY);
                return;
            }
            fallback = b.getLocation().add(0.5, 1, 0.5);
        }

        final int bolts = (int) Math.ceil(s.stacks / 2.0);
        final double dmg = d(Rune.WEATHER, "bolt-damage", 5);
        final double radius = d(Rune.WEATHER, "bolt-radius", 2.5);
        final List<LivingEntity> targets = cands;
        final Location fb = fallback;

        new BukkitRunnable() {
            int n = 0;
            @Override
            public void run() {
                if (n >= bolts || !p.isOnline()) {
                    cancel();
                    return;
                }
                Location at = null;
                if (!targets.isEmpty()) {
                    LivingEntity t = targets.get(n % targets.size());
                    if (t.isValid()) at = t.getLocation();
                }
                if (at == null && fb != null) {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    at = fb.clone().add(r.nextDouble(-2, 2), 0, r.nextDouble(-2, 2));
                }
                if (at != null) strike(p, at, radius, dmg);
                n++;
            }
        }.runTaskTimer(this, 0L, 6L);

        bar(p, "STORM CALL! " + bolts + " bolt" + (bolts > 1 ? "s" : "") + "!", NamedTextColor.LIGHT_PURPLE);
        end(p, s, 1.0);
    }

    private void changeWeather(Player p) {
        if (!getConfig().getBoolean("runes.weather.allow-weather-change", true)) return;
        if (!ready(p, "weather-change")) return;
        World w = p.getWorld();
        String result;
        if (!w.hasStorm()) {
            w.setStorm(true);
            w.setThundering(false);
            w.setWeatherDuration(6000);
            result = "rain";
        } else if (!w.isThundering()) {
            w.setThundering(true);
            w.setThunderDuration(6000);
            w.setWeatherDuration(6000);
            result = "a thunderstorm";
        } else {
            w.setStorm(false);
            w.setThundering(false);
            result = "clear skies";
        }
        for (Player pl : w.getPlayers()) {
            pl.sendMessage(Component.text(p.getName() + " changed the weather to " + result + ".", NamedTextColor.LIGHT_PURPLE));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.2f);
        startCd(p, "weather-change", d(Rune.WEATHER, "weather-change-cooldown-seconds", 120));
    }

    // ----------------------------------------------------------------- FIRE

    private void releaseFire(Player p, State s) {
        int min = i(Rune.FIRE, "min-total-stacks", 2);
        if (s.stacks < min) {
            bar(p, "Need at least " + min + " ember stacks (" + s.stacks + ")", NamedTextColor.GRAY);
            return;
        }
        double range = d(Rune.FIRE, "detonation-range", 25);
        double perStack = d(Rune.FIRE, "damage-per-stack", 1.5);
        int burn = (int) (d(Rune.FIRE, "burn-seconds", 4) * 20);

        Map<LivingEntity, Integer> inRange = new HashMap<>();
        for (Map.Entry<UUID, Integer> en : s.marks.entrySet()) {
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent instanceof LivingEntity le && le.isValid() && le.getWorld().equals(p.getWorld())
                    && le.getLocation().distanceSquared(p.getLocation()) <= range * range) {
                inRange.put(le, en.getValue());
            }
        }
        if (inRange.isEmpty()) {
            bar(p, "No marked enemies in range.", NamedTextColor.GRAY);
            return;
        }

        double total = 0;
        for (Map.Entry<LivingEntity, Integer> en : inRange.entrySet()) {
            LivingEntity t = en.getKey();
            double dmg = en.getValue() * perStack;
            total += dmg;
            Vector push = flatAway(p.getLocation(), t.getLocation()).multiply(0.5);
            push.setY(0.3);
            hurt(p, t, dmg);
            t.setFireTicks(burn);
            t.setVelocity(push);
            Location c = t.getLocation().add(0, t.getHeight() / 2, 0);
            t.getWorld().spawnParticle(Particle.FLAME, c, 60, 0.4, 0.5, 0.4, 0.12);
            t.getWorld().spawnParticle(Particle.LAVA, c, 12, 0.4, 0.5, 0.4, 0);
            t.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f);
        }
        effect(p, PotionEffectType.FIRE_RESISTANCE, (int) (d(Rune.FIRE, "fire-resistance-seconds", 6) * 20), 0);
        p.playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
        bar(p, "DETONATION! " + inRange.size() + " target" + (inRange.size() > 1 ? "s" : "")
                + ", " + String.format("%.1f", total) + " total damage", NamedTextColor.GOLD);
        end(p, s, 1.0);
    }

    // ---------------------------------------------------------------- EARTH

    private boolean isEarthBlock(Material m) {
        String n = m.name();
        for (String k : new String[]{"STONE", "DEEPSLATE", "_ORE", "DIRT", "GRAVEL", "GRANITE", "DIORITE",
                "ANDESITE", "TUFF", "NETHERRACK", "BASALT", "BLACKSTONE", "CLAY", "SAND"}) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    private void releaseEarth(Player p, State s) {
        int min = i(Rune.EARTH, "min-stacks", 3);
        int stacks = s.stacks;
        if (stacks < min) {
            bar(p, "Need at least " + min + " stacks - go mine! (" + stacks + ")", NamedTextColor.GRAY);
            return;
        }
        double radius = Math.min(d(Rune.EARTH, "quake-radius-max", 6),
                d(Rune.EARTH, "quake-radius-base", 3) + stacks * d(Rune.EARTH, "quake-radius-per-stack", 0.25));
        double dmg = d(Rune.EARTH, "quake-damage-base", 1.5) + stacks * d(Rune.EARTH, "quake-damage-per-stack", 0.35);
        int slow = (int) ((d(Rune.EARTH, "slow-base-seconds", 2) + stacks * d(Rune.EARTH, "slow-seconds-per-stack", 0.2)) * 20);

        for (LivingEntity t : nearby(p, radius)) {
            hurt(p, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1));
            t.setVelocity(t.getVelocity().setY(0.5));
        }
        double shield = stacks * d(Rune.EARTH, "absorption-per-stack", 1.0);
        p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), shield));
        int resTicks = (int) ((d(Rune.EARTH, "resistance-base-seconds", 5) + stacks * d(Rune.EARTH, "resistance-seconds-per-stack", 0.8)) * 20);
        effect(p, PotionEffectType.RESISTANCE, resTicks, 0);

        p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0, 0.2, 0), 150, radius / 2, 0.3, radius / 2, 0.15,
                Material.DIRT.createBlockData());
        p.playSound(p.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1.3f, 0.5f);
        p.playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.6f);
        bar(p, "BEDROCK x" + stacks + " - " + String.format("%.1f", shield / 2) + " hearts of shield!", NamedTextColor.DARK_GREEN);
        end(p, s, 1.0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (placedBlocks.size() > 20000) placedBlocks.clear();
        placedBlocks.add(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        boolean wasPlaced = placedBlocks.remove(b.getLocation());
        State s = states.get(p.getUniqueId());
        if (s == null || s.phase != Phase.CHARGING || s.active != Rune.EARTH) return;
        if (wasPlaced || p.getGameMode() == GameMode.CREATIVE || !isEarthBlock(b.getType())) return;
        int max = i(Rune.EARTH, "max-stacks", 10);
        if (s.stacks >= max) return;
        s.stacks++;
        s.lastGain = System.currentTimeMillis();
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.7f + 0.08f * s.stacks);
        p.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0.05,
                b.getType().createBlockData());
    }

    // ------------------------------------------------------------------ AIR

    private void releaseAir(Player p, State s) {
        int min = i(Rune.AIR, "min-stacks", 2);
        int stacks = s.stacks;
        if (stacks < min) {
            bar(p, "Need at least " + min + " wind stacks - jump! (" + stacks + ")", NamedTextColor.GRAY);
            return;
        }
        // gust first, from where you stand
        double gr = d(Rune.AIR, "gust-radius", 4);
        double gkb = Math.min(d(Rune.AIR, "gust-knockback-max", 1.6),
                d(Rune.AIR, "gust-knockback-base", 0.5) + stacks * d(Rune.AIR, "gust-knockback-per-stack", 0.15));
        for (LivingEntity t : nearby(p, gr)) {
            Vector push = flatAway(p.getLocation(), t.getLocation()).multiply(gkb);
            push.setY(0.5);
            t.setVelocity(push);
        }

        Vector h = p.getLocation().getDirection();
        h.setY(0);
        h = h.lengthSquared() < 0.0001 ? new Vector(0, 0, 0) : h.normalize();
        double up = Math.min(d(Rune.AIR, "max-launch", 1.7),
                d(Rune.AIR, "launch-base", 0.7) + stacks * d(Rune.AIR, "launch-per-stack", 0.15));
        double fwd = d(Rune.AIR, "forward-base", 0.3) + stacks * d(Rune.AIR, "forward-per-stack", 0.1);
        Vector v = h.multiply(fwd);
        v.setY(up);
        p.setVelocity(v);
        p.setFallDistance(0);
        effect(p, PotionEffectType.SLOW_FALLING, (int) (d(Rune.AIR, "slow-falling-seconds", 12) * 20), 0);

        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 80, gr / 3, 0.2, gr / 3, 0.12);
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 0.9f);
        p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.2f);
        bar(p, "GALE x" + stacks + "!", NamedTextColor.WHITE);
        end(p, s, 1.0);
    }

    // ----------------------------------------------------- combat listeners

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (internalDamage) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        State s = states.get(p.getUniqueId());
        if (s == null || s.phase == Phase.IDLE) return;
        long now = System.currentTimeMillis();

        // ---- FIRE: mark the target, damage stays normal
        if (s.phase == Phase.CHARGING && s.active == Rune.FIRE) {
            if (!valid(p, target)) return;
            boolean solid = p.getAttackCooldown() >= d(Rune.FIRE, "hit-min-attack-strength", 0.7)
                    && target.getNoDamageTicks() <= target.getMaximumNoDamageTicks() / 2;
            int cap = i(Rune.FIRE, "max-stacks-per-target", 5);
            int cur = s.marks.getOrDefault(target.getUniqueId(), 0);
            if (solid && cur < cap) {
                s.marks.put(target.getUniqueId(), cur + 1);
                s.stacks = s.marks.values().stream().mapToInt(Integer::intValue).sum();
                s.lastGain = now;
                p.playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.2f + 0.1f * (cur + 1));
                target.getWorld().spawnParticle(Particle.FLAME,
                        target.getLocation().add(0, target.getHeight() / 2, 0), 12, 0.3, 0.4, 0.3, 0.04);
            }
            return;
        }

        // ---- STRENGTH
        if (s.active != Rune.STRENGTH) return;

        if (s.phase == Phase.CHARGING) {
            if (runeOf(p.getInventory().getItemInOffHand()) != Rune.STRENGTH) {
                end(p, s, 0);
                bar(p, "Rune removed - charge lost.", NamedTextColor.GRAY);
                return;
            }
            long timeout = (long) (d(Rune.STRENGTH, "combo-timeout-seconds", 6) * 1000);
            int max = i(Rune.STRENGTH, "max-stacks", 8);
            if (s.stacks > 0 && now - s.lastGain > timeout) s.stacks = 0;

            boolean solid = p.getAttackCooldown() >= d(Rune.STRENGTH, "min-attack-strength", 0.9)
                    && target.getNoDamageTicks() <= target.getMaximumNoDamageTicks() / 2;
            e.setDamage(0);
            if (solid && s.stacks < max) {
                s.stacks++;
                s.lastGain = now;
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, Math.min(2f, 0.8f + 0.1f * s.stacks));
                target.getWorld().spawnParticle(Particle.ENCHANTED_HIT,
                        target.getLocation().add(0, target.getHeight() / 2, 0), 8, 0.3, 0.3, 0.3, 0.1);
            }
            return;
        }

        if (s.phase == Phase.READY) {
            if (now > s.readyUntil) {
                end(p, s, 0.5);
                return;
            }
            int max = i(Rune.STRENGTH, "max-stacks", 8);
            double bonus = s.stacks * d(Rune.STRENGTH, "damage-per-stack", 0.75);
            if (s.stacks >= max) bonus += d(Rune.STRENGTH, "full-charge-bonus", 1.0);
            bonus = Math.min(bonus, d(Rune.STRENGTH, "max-bonus-damage", 8.0));

            e.setDamage(e.getDamage() + bonus);
            bar(p, "Strength Rune hit! +" + String.format("%.1f", bonus) + " damage", NamedTextColor.GOLD);
            p.getWorld().playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
            target.getWorld().spawnParticle(Particle.CRIT,
                    target.getLocation().add(0, target.getHeight() / 2, 0), 30, 0.4, 0.5, 0.4, 0.3);
            end(p, s, 1.0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVictim(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        State s = states.get(p.getUniqueId());
        if (s == null || s.phase != Phase.CHARGING) return;

        if (s.active == Rune.WATER) {
            EntityDamageEvent.DamageCause c = e.getCause();
            if (c == EntityDamageEvent.DamageCause.VOID || c == EntityDamageEvent.DamageCause.SUICIDE
                    || c == EntityDamageEvent.DamageCause.KILL) return;
            double base = e.getDamage();
            if (base <= 0) return;
            s.pool = Math.min(d(Rune.WATER, "max-pool", 14), s.pool + base * d(Rune.WATER, "store-fraction", 0.5));
            e.setDamage(base * (1 - d(Rune.WATER, "damage-reduction", 0.25)));
            p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.1);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.6f, 1.3f);
        } else if (s.active == Rune.STRENGTH && getConfig().getBoolean("runes.strength.reset-on-hurt", false)
                && e.getFinalDamage() > 0) {
            end(p, s, 0);
            bar(p, "You were hit - charge lost!", NamedTextColor.GRAY);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        State s = states.get(p.getUniqueId());
        if (s != null && s.phase != Phase.IDLE) end(p, s, 0);
        states.remove(p.getUniqueId());
        cooldowns.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        State s = states.get(p.getUniqueId());
        if (s != null && s.phase != Phase.IDLE) end(p, s, 0);
    }

    // --------------------------------------------------------------- ticker

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null || s.phase == Phase.IDLE || s.active == null) continue;
            Rune r = s.active;

            if (s.phase == Phase.READY) {
                if (now > s.readyUntil) {
                    end(p, s, 0.5);
                    bar(p, "Charged hit expired.", NamedTextColor.GRAY);
                } else {
                    bar(p, "CHARGED x" + s.stacks + " - " + ((s.readyUntil - now + 999) / 1000) + "s left", NamedTextColor.GOLD);
                }
                continue;
            }

            if (runeOf(p.getInventory().getItemInOffHand()) != r) {
                end(p, s, 0);
                bar(p, "Rune removed - charge lost.", NamedTextColor.GRAY);
                continue;
            }

            double maxCharge = d(r, "max-charge-seconds", defaultMaxCharge(r));
            if (maxCharge > 0 && now - s.startedAt > maxCharge * 1000) {
                end(p, s, 0.4);
                p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1f);
                bar(p, r.display + " fizzled out.", NamedTextColor.GRAY);
                continue;
            }

            switch (r) {
                case STRENGTH -> {
                    long timeout = (long) (d(Rune.STRENGTH, "combo-timeout-seconds", 6) * 1000);
                    if (s.stacks > 0 && now - s.lastGain > timeout) {
                        s.stacks = 0;
                        bar(p, "Combo expired - stacks reset.", NamedTextColor.GRAY);
                        continue;
                    }
                }
                case SPEED -> tickSpeed(p, s, now);
                case WEATHER -> tickWeather(p, s, now);
                case AIR -> tickAir(p, s, now);
                default -> { }
            }
            showBar(p, s);
        }
    }

    private void tickSpeed(Player p, State s, long now) {
        double bps = d(Rune.SPEED, "blocks-per-stack", 10);
        int max = i(Rune.SPEED, "max-stacks", 8);
        Location cur = p.getLocation();
        if (s.lastLoc != null && s.lastLoc.getWorld() == cur.getWorld()) {
            double dx = cur.getX() - s.lastLoc.getX();
            double dz = cur.getZ() - s.lastLoc.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (p.isSprinting() && dist > 0.05 && dist < 8) {
                s.pool += dist;
                s.lastActive = now;
            }
        }
        s.lastLoc = cur.clone();
        if (!p.isSprinting() && now - s.lastActive > d(Rune.SPEED, "decay-delay-seconds", 3) * 1000) {
            s.pool = Math.max(0, s.pool - bps * 0.25);
        }
        s.pool = Math.min(s.pool, max * bps);
        int newStacks = (int) (s.pool / bps);
        if (newStacks > s.stacks) {
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f + 0.1f * newStacks);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.2, 0), 8, 0.3, 0.1, 0.3, 0.02);
        }
        s.stacks = newStacks;
    }

    private void tickWeather(Player p, State s, long now) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30,
                i(Rune.WEATHER, "slowness-amplifier", 0), true, false, false));
        int max = i(Rune.WEATHER, "max-stacks", 6);
        double per = p.getWorld().hasStorm()
                ? d(Rune.WEATHER, "storm-seconds-per-stack", 1.0)
                : d(Rune.WEATHER, "seconds-per-stack", 2.0);
        if (s.stacks < max && now - s.lastGain >= per * 1000) {
            s.stacks++;
            s.lastGain = now;
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.7f + 0.15f * s.stacks);
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 2.2, 0), 15, 0.3, 0.2, 0.3, 0.1);
        }
    }

    private void tickAir(Player p, State s, long now) {
        int max = i(Rune.AIR, "max-stacks", 6);
        int cur = p.getStatistic(Statistic.JUMP);
        int delta = cur - s.jumpSeen;
        s.jumpSeen = cur;
        if (delta > 0) {
            if (s.stacks < max) {
                s.stacks = Math.min(max, s.stacks + delta);
                p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.7f, 1f + 0.1f * s.stacks);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 10, 0.3, 0.1, 0.3, 0.03);
            }
            s.lastGain = now;
        } else if (s.stacks > 0 && now - s.lastGain > d(Rune.AIR, "jump-timeout-seconds", 6) * 1000) {
            s.stacks--;
            s.lastGain = now;
        }
    }

    private void showBar(Player p, State s) {
        Rune r = s.active;
        String text = switch (r) {
            case STRENGTH -> "Strength stacks: " + s.stacks + "/" + i(r, "max-stacks", 8);
            case SPEED -> "Momentum: " + s.stacks + "/" + i(r, "max-stacks", 8) + (p.isSprinting() ? "  (sprinting!)" : "  (sprint to build)");
            case WATER -> "Tide: " + String.format("%.1f", s.pool) + "/" + String.format("%.1f", d(r, "max-pool", 14)) + " stored";
            case WEATHER -> "Storm: " + s.stacks + "/" + i(r, "max-stacks", 6) + (p.getWorld().hasStorm() ? "  (rain boost)" : "");
            case FIRE -> "Ember marks: " + s.stacks + " on " + s.marks.size() + " target" + (s.marks.size() == 1 ? "" : "s");
            case EARTH -> "Bedrock: " + s.stacks + "/" + i(r, "max-stacks", 10);
            case AIR -> "Gale: " + s.stacks + "/" + i(r, "max-stacks", 6) + "  (jump!)";
        };
        long left = (long) (d(r, "max-charge-seconds", defaultMaxCharge(r)) * 1000 - (System.currentTimeMillis() - s.startedAt));
        if (r != Rune.STRENGTH && left > 0) text += "  [" + ((left + 999) / 1000) + "s]";
        bar(p, text, r.color);
    }

    // ======================================================== SCROLLS & TRIALS

    @Override
    public void onDisable() {
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();
    }

    private int defaultGoal(Rune r) {
        return switch (r) {
            case STRENGTH, SPEED, EARTH -> 4;
            default -> 3;
        };
    }

    private int goal(Rune r) {
        return Math.max(1, getConfig().getInt("trials." + r.id() + ".kills", defaultGoal(r)));
    }

    private String trialDescription(Rune r) {
        return r.trialText.replace("{n}", String.valueOf(goal(r)));
    }

    private Material defaultSide(Rune r) {
        return switch (r) {
            case STRENGTH -> Material.DIAMOND;
            case SPEED -> Material.PHANTOM_MEMBRANE;
            case WATER -> Material.PRISMARINE_CRYSTALS;
            case WEATHER -> Material.LIGHTNING_ROD;
            case FIRE -> Material.BLAZE_ROD;
            case EARTH -> Material.EMERALD;
            case AIR -> Material.BREEZE_ROD;
        };
    }

    private Material recipeMat(Rune r, String part, Material def) {
        String name = getConfig().getString("scrolls.recipes." + r.id() + "." + part);
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return (m != null && m.isItem() && !m.isAir()) ? m : def;
    }

    private String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String describeRecipe(Rune r) {
        return r.display + " Scroll: 4x " + pretty(recipeMat(r, "corner", Material.AMETHYST_SHARD))
                + " (corners), 4x " + pretty(recipeMat(r, "side", defaultSide(r)))
                + " (sides), 1x " + pretty(recipeMat(r, "center", Material.PAPER)) + " (center)";
    }

    private void registerRecipes() {
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();
        for (Rune r : Rune.values()) {
            NamespacedKey key = new NamespacedKey(this, "scroll_" + r.id());
            ShapedRecipe rec = new ShapedRecipe(key, createScroll(r));
            rec.shape("CSC", "SPS", "CSC");
            rec.setIngredient('C', recipeMat(r, "corner", Material.AMETHYST_SHARD));
            rec.setIngredient('S', recipeMat(r, "side", defaultSide(r)));
            rec.setIngredient('P', recipeMat(r, "center", Material.PAPER));
            Bukkit.removeRecipe(key);
            Bukkit.addRecipe(rec);
            recipeKeys.add(key);
        }
    }

    private ItemStack createScroll(Rune r) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(r.display + " Scroll", r.color)
                .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Right-click to begin the trial.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Trial:", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(trialDescription(r), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Complete it to earn the " + r.display + ".", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(scrollKey, PersistentDataType.STRING, r.id());
        item.setItemMeta(meta);
        return item;
    }

    private Rune scrollOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        return Rune.fromId(item.getItemMeta().getPersistentDataContainer().get(scrollKey, PersistentDataType.STRING));
    }

    private void giveItem(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // ---- trial storage (saved on the player, survives restarts)

    private Rune trialOf(Player p) {
        return Rune.fromId(p.getPersistentDataContainer().get(trialKey, PersistentDataType.STRING));
    }

    private int progress(Player p) {
        Integer v = p.getPersistentDataContainer().get(progressKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private void setProgress(Player p, int v) {
        p.getPersistentDataContainer().set(progressKey, PersistentDataType.INTEGER, v);
    }

    // ---- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.discoverRecipes(recipeKeys);
        Rune t = trialOf(p);
        if (t != null) {
            p.sendMessage(Component.text("Active trial - " + t.display + ": " + trialDescription(t)
                    + " (" + progress(p) + "/" + goal(t) + ")", t.color));
        }
    }

    /** Scrolls and runes can't be used as crafting ingredients (stops paper-center exploits). */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it != null && (scrollOf(it) != null || runeOf(it) != null)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onScroll(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        Player p = e.getPlayer();
        ItemStack item = hand == EquipmentSlot.HAND
                ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
        Rune r = scrollOf(item);
        if (r == null) return;

        if (a == Action.RIGHT_CLICK_BLOCK && !p.isSneaking()
                && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) return;
        e.setCancelled(true);
        if (!p.hasPermission("runes.use")) return;

        Rune current = trialOf(p);
        if (current != null) {
            bar(p, "You already have a trial: " + current.display + " (" + progress(p) + "/" + goal(current)
                    + "). Use /rune trial abandon to quit it.", NamedTextColor.GRAY);
            return;
        }

        // consume one scroll
        ItemStack left = item.clone();
        left.setAmount(item.getAmount() - 1);
        ItemStack newStack = left.getAmount() <= 0 ? null : left;
        if (hand == EquipmentSlot.HAND) p.getInventory().setItemInMainHand(newStack);
        else p.getInventory().setItemInOffHand(newStack);

        p.getPersistentDataContainer().set(trialKey, PersistentDataType.STRING, r.id());
        setProgress(p, 0);

        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        p.showTitle(Title.title(
                Component.text(r.display + " Trial", r.color),
                Component.text("The scroll crumbles... your trial begins", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.sendMessage(Component.text("Trial started: ", NamedTextColor.GOLD)
                .append(Component.text(trialDescription(r) + " (0/" + goal(r) + ")", r.color)));
        p.sendMessage(Component.text("Check progress with /rune trial.", NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            e.getEntity().getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player p = dead.getKiller();
        if (p == null) return;
        Rune t = trialOf(p);
        if (t == null) return;

        if (dead instanceof Player) {
            if (!getConfig().getBoolean("scrolls.count-player-kills", false) || dead.equals(p)) return;
        } else if (!(dead instanceof Enemy) || dead instanceof Slime) {
            return; // only hostile mobs (slimes excluded: they split into many kills)
        }
        if (getConfig().getBoolean("scrolls.ignore-spawner-mobs", true)
                && dead.getPersistentDataContainer().has(spawnerKey, PersistentDataType.BYTE)) return;

        if (!conditionMet(t, p)) {
            bar(p, "That kill didn't count - " + trialDescription(t), NamedTextColor.GRAY);
            return;
        }

        int prog = progress(p) + 1;
        if (prog >= goal(t)) {
            completeTrial(p, t);
        } else {
            setProgress(p, prog);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f + 0.1f * prog);
            bar(p, t.display + " trial: " + prog + "/" + goal(t), t.color);
        }
    }

    /** Strength trial: any damage taken breaks the flawless streak. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrialHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (trialOf(p) != Rune.STRENGTH || progress(p) == 0 || e.getFinalDamage() <= 0) return;
        setProgress(p, 0);
        bar(p, "Flawless streak broken! Strength trial reset to 0/" + goal(Rune.STRENGTH), NamedTextColor.RED);
    }

    private boolean conditionMet(Rune t, Player p) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        boolean overworld = w.getEnvironment() == World.Environment.NORMAL;
        return switch (t) {
            case STRENGTH -> true;
            case SPEED -> p.isSprinting();
            case WATER -> p.isInWater()
                    || (w.hasStorm() && overworld && loc.getBlock().getLightFromSky() >= 15);
            case WEATHER -> w.isThundering();
            case FIRE -> p.getFireTicks() > 0 || w.getEnvironment() == World.Environment.NETHER;
            case EARTH -> overworld && loc.getY() < 40 && loc.getBlock().getLightFromSky() == 0;
            case AIR -> !p.isOnGround() && !p.isInWater()
                    && (p.isGliding() || w.rayTraceBlocks(loc, new Vector(0, -1, 0), 4.0) == null);
        };
    }

    private void completeTrial(Player p, Rune r) {
        p.getPersistentDataContainer().remove(trialKey);
        p.getPersistentDataContainer().remove(progressKey);
        giveItem(p, createRune(r));

        p.showTitle(Title.title(
                Component.text(r.display + " obtained!", r.color),
                Component.text("Trial complete", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.3);
        if (getConfig().getBoolean("scrolls.announce-completion", true)) {
            Bukkit.getServer().broadcast(Component.text(p.getName() + " has attained the " + r.display + "!", r.color));
        }
    }

    private void showTrial(Player p) {
        Rune t = trialOf(p);
        if (t == null) {
            p.sendMessage(Component.text("You have no active trial. Craft a Rune Scroll and right-click it. (/rune recipe <rune>)", NamedTextColor.GRAY));
            return;
        }
        p.sendMessage(Component.text(t.display + " trial: " + trialDescription(t)
                + " (" + progress(p) + "/" + goal(t) + ")", t.color));
    }

    private void abandonTrial(Player p) {
        Rune t = trialOf(p);
        if (t == null) {
            p.sendMessage(Component.text("You have no active trial.", NamedTextColor.GRAY));
            return;
        }
        p.getPersistentDataContainer().remove(trialKey);
        p.getPersistentDataContainer().remove(progressKey);
        giveItem(p, createScroll(t));
        p.sendMessage(Component.text("Trial abandoned. Your " + t.display + " Scroll was returned.", NamedTextColor.YELLOW));
    }

    // -------------------------------------------------------------- command

    private boolean noPerm(CommandSender s) {
        if (s.hasPermission("runes.give")) return false;
        s.sendMessage(Component.text("No permission.", NamedTextColor.RED));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (sub.equals("list")) {
            StringBuilder sb = new StringBuilder();
            for (Rune r : Rune.values()) sb.append(r.id()).append(' ');
            sender.sendMessage(Component.text("Runes: " + sb.toString().trim(), NamedTextColor.YELLOW));
            return true;
        }

        if (sub.equals("reload")) {
            if (noPerm(sender)) return true;
            reloadConfig();
            registerRecipes();
            sender.sendMessage(Component.text("Runes config and recipes reloaded.", NamedTextColor.GREEN));
            return true;
        }

        if (sub.equals("recipe")) {
            Rune r = args.length > 1 ? Rune.fromId(args[1]) : null;
            if (r == null) {
                sender.sendMessage(Component.text("Usage: /rune recipe <rune>   (pattern: C S C / S P S / C S C)", NamedTextColor.YELLOW));
                return true;
            }
            sender.sendMessage(Component.text(describeRecipe(r), r.color));
            sender.sendMessage(Component.text("Trial: " + trialDescription(r), NamedTextColor.GRAY));
            return true;
        }

        if (sub.equals("trial")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                return true;
            }
            if (args.length > 1 && args[1].equalsIgnoreCase("abandon")) abandonTrial(p);
            else showTrial(p);
            return true;
        }

        if (sub.equals("give") || sub.equals("scroll")) {
            if (noPerm(sender)) return true;
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /rune " + sub + " <player> <rune|all>", NamedTextColor.YELLOW));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            List<Rune> list = new ArrayList<>();
            if (args[2].equalsIgnoreCase("all")) {
                list.addAll(List.of(Rune.values()));
            } else {
                Rune r = Rune.fromId(args[2]);
                if (r == null) {
                    sender.sendMessage(Component.text("Unknown rune. Use /rune list", NamedTextColor.RED));
                    return true;
                }
                list.add(r);
            }
            boolean scroll = sub.equals("scroll");
            for (Rune r : list) giveItem(target, scroll ? createScroll(r) : createRune(r));
            sender.sendMessage(Component.text("Gave " + (list.size() == 1 ? list.get(0).display + (scroll ? " Scroll" : "")
                    : list.size() + (scroll ? " scrolls" : " runes")) + " to " + target.getName() + ".", NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("/rune trial [abandon] | recipe <rune> | list | give <player> <rune|all> | scroll <player> <rune|all> | reload", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("trial", "recipe", "list", "give", "scroll", "reload"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give", "scroll" -> Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
                case "recipe" -> { for (Rune r : Rune.values()) out.add(r.id()); }
                case "trial" -> out.add("abandon");
                default -> { }
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("scroll"))) {
            for (Rune r : Rune.values()) out.add(r.id());
            out.add("all");
        }
        return out;
    }
}
