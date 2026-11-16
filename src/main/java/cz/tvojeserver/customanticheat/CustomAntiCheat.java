package cz.tvojeserver.customanticheat;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CustomAntiCheat extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Long> lastAttackTime = new HashMap<>();
    private final Map<UUID, Integer> cpsCounter = new HashMap<>();
    private final Map<UUID, Long> lastAlertTime = new HashMap<>();

    private final String PREFIX = ChatColor.DARK_RED + "[" + ChatColor.RED + "AntiCheat" + ChatColor.DARK_RED + "] " + ChatColor.GRAY;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("ac").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CustomAntiCheat byl uspesne zapnut!");
    }

    private boolean isExempt(Player player) {
        return player.getGameMode() == GameMode.CREATIVE ||
               player.getGameMode() == GameMode.SPECTATOR ||
               player.getAllowFlight() ||
               player.isGliding() ||
               player.isInsideVehicle() ||
               player.hasPermission("anticheat.bypass");
    }

    // 1. KONTROLA POHYBU: Fly, Speed, Jesus (chůze po vodě)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();
        double horizontalSpeed = Math.hypot(deltaX, deltaZ);

        // --- SPEED CHECK ---
        double maxSpeed = 0.72; // Základní limit pro sprint + skákání
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            maxSpeed += amp * 0.18;
        }
        if (player.getLocation().getBlock().getRelative(0, -1, 0).getType().name().contains("ICE")) {
            maxSpeed += 0.45;
        }

        // Ignorujeme odhození (knockback / exploze)
        if (horizontalSpeed > maxSpeed && player.getNoDamageTicks() == 0) {
            event.setTo(from);
            flag(player, "Speed", String.format("rychlost %.2f (max %.2f)", horizontalSpeed, maxSpeed), 2);
            return;
        }

        // --- FLY / HOVER CHECK ---
        Block below = to.clone().subtract(0, 0.3, 0).getBlock();
        Block atFeet = to.getBlock();
        boolean inLiquid = atFeet.isLiquid() || below.isLiquid();
        boolean onClimbable = atFeet.getType() == Material.LADDER || atFeet.getType() == Material.VINE || atFeet.getType() == Material.SCAFFOLDING;

        if (below.getType().isAir() && !inLiquid && !onClimbable && !player.isOnGround()) {
            int ticks = airTicks.getOrDefault(player.getUniqueId(), 0) + 1;
            airTicks.put(player.getUniqueId(), ticks);

            // Pokud hráč ve vzduchu stoupá nahoru nebo se vznáší bez padání po více než 18 ticích
            if (ticks > 18 && deltaY >= -0.05 && player.getNoDamageTicks() == 0) {
                event.setTo(from);
                flag(player, "Fly / Hover", "vznášení ve vzduchu (" + ticks + " ticků)", 3);
            }
        } else {
            airTicks.put(player.getUniqueId(), 0);
        }
    }

    // 2. KONTROLA SOUBOJŮ: Reach (dosah), KillAura (úhel útoku), AutoClicker (CPS)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;

        Entity target = event.getEntity();

        // --- REACH CHECK ---
        double distance = player.getEyeLocation().distance(target.getLocation().add(0, target.getHeight() / 2.0, 0));
        double maxReach = 3.85; // Normální dosah v survivalu je 3.0 bloky (+ rezerva pro ping)

        if (distance > maxReach) {
            event.setCancelled(true);
            flag(player, "Reach", String.format("dosah %.2f bloků", distance), 4);
            return;
        }

        // --- KILLAURA (ANGLE) CHECK ---
        Vector toTarget = target.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
        Vector direction = player.getLocation().getDirection().normalize();
        double dot = direction.dot(toTarget);

        // Pokud hráč mlátí někoho, na koho se vůbec nedívá (za zády nebo výrazně mimo zorné pole)
        if (distance > 1.8 && dot < 0.25) {
            event.setCancelled(true);
            flag(player, "KillAura", "úder mimo zorné pole", 4);
            return;
        }

        // --- AUTOCLICKER (CPS) CHECK ---
        long now = System.currentTimeMillis();
        long last = lastAttackTime.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 1000) {
            int cps = cpsCounter.getOrDefault(player.getUniqueId(), 0) + 1;
            cpsCounter.put(player.getUniqueId(), cps);
            if (cps > 18) {
                event.setCancelled(true);
                flag(player, "AutoClicker", cps + " CPS", 2);
            }
        } else {
            lastAttackTime.put(player.getUniqueId(), now);
            cpsCounter.put(player.getUniqueId(), 1);
        }
    }

    private void flag(Player player, String checkName, String info, int addedVL) {
        UUID uuid = player.getUniqueId();
        int currentVL = violations.getOrDefault(uuid, 0) + addedVL;
        violations.put(uuid, currentVL);

        int kickLimit = getConfig().getInt("kick-vl", 25);

        // Upozornění pro adminy (max 1x za 2 sekundy na hráče, aby nespamovalo chat)
        long now = System.currentTimeMillis();
        if (now - lastAlertTime.getOrDefault(uuid, 0L) > 2000) {
            lastAlertTime.put(uuid, now);
            String alert = PREFIX + ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " podezření na " +
                    ChatColor.RED + checkName + ChatColor.DARK_GRAY + " (" + info + ") " +
                    ChatColor.GRAY + "[VL: " + ChatColor.RED + currentVL + "/" + kickLimit + ChatColor.GRAY + "]";

            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.isOp() || staff.hasPermission("anticheat.alerts")) {
                    staff.sendMessage(alert);
                }
            }
            getLogger().warning("[AC] " + player.getName() + " -> " + checkName + " (" + info + ") VL: " + currentVL);
        }

        // Automatický kick při překročení limitu
        if (currentVL >= kickLimit) {
            violations.remove(uuid);
            Bukkit.getScheduler().runTask(this, () ->
                    player.kickPlayer(ChatColor.RED + "Byl jsi vyhozen systémem AntiCheat!\n" +
                            ChatColor.GRAY + "Důvod: " + ChatColor.YELLOW + checkName));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        violations.remove(uuid);
        airTicks.remove(uuid);
        cpsCounter.remove(uuid);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin") && sender instanceof Player) {
            sender.sendMessage(ChatColor.RED + "Nemáš oprávnění.");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Hráč není online.");
                return true;
            }
            violations.remove(target.getUniqueId());
            sender.sendMessage(PREFIX + "Trestné body (VL) pro hráče " + ChatColor.GREEN + target.getName() + ChatColor.GRAY + " byly vynulovány.");
            return true;
        }

        sender.sendMessage(PREFIX + "Použití: " + ChatColor.YELLOW + "/ac reset <hráč>");
        return true;
    }
}
