package com.chestshop.managers;

import com.chestshop.ChestShopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages stock and money alerts for shop owners
 */
public class AlertManager {

    private final ChestShopPlugin plugin;
    private final File alertsFile;
    private FileConfiguration alertsConfig;

    // In-memory cache of pending alerts for offline players
    private final Map<UUID, List<String>> pendingAlerts = new HashMap<>();

    // Cooldown tracking to prevent alert spam (shop location -> last alert time)
    private final Map<String, Long> alertCooldowns = new HashMap<>();
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000L; // 5 minutes

    public AlertManager(ChestShopPlugin plugin) {
        this.plugin = plugin;
        this.alertsFile = new File(plugin.getDataFolder(), "alerts.yml");
        loadAlerts();
    }

    /**
     * Check if an alert is on cooldown for a specific shop
     */
    private boolean isOnCooldown(String shopKey) {
        // Periodically clean up expired entries to prevent memory leak
        if (alertCooldowns.size() > 100) {
            cleanupExpiredCooldowns();
        }

        Long lastAlert = alertCooldowns.get(shopKey);
        if (lastAlert == null) {
            return false;
        }
        return (System.currentTimeMillis() - lastAlert) < ALERT_COOLDOWN_MS;
    }

    /**
     * Mark an alert as sent for cooldown tracking
     */
    private void markAlertSent(String shopKey) {
        alertCooldowns.put(shopKey, System.currentTimeMillis());
    }

    /**
     * Remove expired cooldown entries to prevent memory leak
     */
    private void cleanupExpiredCooldowns() {
        long now = System.currentTimeMillis();
        alertCooldowns.entrySet().removeIf(entry ->
                (now - entry.getValue()) >= ALERT_COOLDOWN_MS);
    }

    /**
     * Send a low stock alert to the shop owner
     */
    public void sendLowStockAlert(UUID ownerUUID, String ownerName, String itemName, int remaining, String shopLocation) {
        String cooldownKey = "stock:" + shopLocation;
        if (isOnCooldown(cooldownKey)) {
            return; // Skip - already alerted recently
        }

        String message = ChatColor.YELLOW + "⚠ " + ChatColor.GOLD + "Low Stock Alert: " +
                ChatColor.WHITE + "Your " + ChatColor.AQUA + itemName + ChatColor.WHITE +
                " shop only has " + ChatColor.RED + remaining + ChatColor.WHITE + " items left!" +
                ChatColor.GRAY + " (" + shopLocation + ")";

        sendAlert(ownerUUID, message);
        markAlertSent(cooldownKey);
    }

    /**
     * Send a low money alert to the shop owner (for buy shops)
     */
    public void sendLowMoneyAlert(UUID ownerUUID, String ownerName, String itemName, double balance, String shopLocation) {
        String cooldownKey = "money:" + shopLocation;
        if (isOnCooldown(cooldownKey)) {
            return; // Skip - already alerted recently
        }

        String message = ChatColor.YELLOW + "⚠ " + ChatColor.GOLD + "Low Funds Alert: " +
                ChatColor.WHITE + "You only have " + ChatColor.RED + "$" + String.format("%.2f", balance) +
                ChatColor.WHITE + " to buy items at your " + ChatColor.AQUA + itemName + ChatColor.WHITE + " shop!" +
                ChatColor.GRAY + " (" + shopLocation + ")";

        sendAlert(ownerUUID, message);
        markAlertSent(cooldownKey);
    }

    /**
     * Send an out of stock alert
     */
    public void sendOutOfStockAlert(UUID ownerUUID, String ownerName, String itemName, String shopLocation) {
        String cooldownKey = "outofstock:" + shopLocation;
        if (isOnCooldown(cooldownKey)) {
            return; // Skip - already alerted recently
        }

        String message = ChatColor.RED + "⚠ " + ChatColor.DARK_RED + "Out of Stock: " +
                ChatColor.WHITE + "Your " + ChatColor.AQUA + itemName + ChatColor.WHITE +
                " shop is now " + ChatColor.RED + "OUT OF STOCK" + ChatColor.WHITE + "!" +
                ChatColor.GRAY + " (" + shopLocation + ")";

        sendAlert(ownerUUID, message);
        markAlertSent(cooldownKey);
    }

    /**
     * Send a shop full alert (can't accept more items)
     */
    public void sendShopFullAlert(UUID ownerUUID, String ownerName, String itemName, String shopLocation) {
        String cooldownKey = "full:" + shopLocation;
        if (isOnCooldown(cooldownKey)) {
            return; // Skip - already alerted recently
        }

        String message = ChatColor.YELLOW + "⚠ " + ChatColor.GOLD + "Shop Full: " +
                ChatColor.WHITE + "Your " + ChatColor.AQUA + itemName + ChatColor.WHITE +
                " shop chest is " + ChatColor.YELLOW + "FULL" + ChatColor.WHITE + " and can't accept more items!" +
                ChatColor.GRAY + " (" + shopLocation + ")";

        sendAlert(ownerUUID, message);
        markAlertSent(cooldownKey);
    }

    /**
     * Send an alert - immediately if online, or save for later
     */
    private void sendAlert(UUID ownerUUID, String message) {
        Player owner = Bukkit.getPlayer(ownerUUID);

        if (owner != null && owner.isOnline()) {
            // Owner is online - send immediately
            owner.sendMessage(message);
        } else {
            // Owner is offline - save for later
            saveAlertForLater(ownerUUID, message);
        }
    }

    /**
     * Save an alert for when the player logs in
     */
    private void saveAlertForLater(UUID playerUUID, String message) {
        pendingAlerts.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(message);
        saveAlerts();
    }

    /**
     * Show pending alerts to a player when they log in
     */
    public void showPendingAlerts(Player player) {
        UUID playerUUID = player.getUniqueId();
        List<String> alerts = pendingAlerts.remove(playerUUID);

        if (alerts != null && !alerts.isEmpty()) {
            // Delay slightly so other login messages appear first
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.sendMessage("");
                player.sendMessage(ChatColor.GOLD + "╔════════════════════════════════════╗");
                player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.YELLOW + "       📦 Shop Alerts (" + alerts.size() + ")" + ChatColor.GOLD + "         ║");
                player.sendMessage(ChatColor.GOLD + "╚════════════════════════════════════╝");

                for (String alert : alerts) {
                    player.sendMessage(alert);
                }

                player.sendMessage("");
            }, 20L); // 1 second delay

            saveAlerts();
        }
    }

    /**
     * Check if a player has pending alerts
     */
    public boolean hasPendingAlerts(UUID playerUUID) {
        List<String> alerts = pendingAlerts.get(playerUUID);
        return alerts != null && !alerts.isEmpty();
    }

    /**
     * Get the count of pending alerts for a player
     */
    public int getPendingAlertCount(UUID playerUUID) {
        List<String> alerts = pendingAlerts.get(playerUUID);
        return alerts != null ? alerts.size() : 0;
    }

    /**
     * Clear all alerts for a player
     */
    public void clearAlerts(UUID playerUUID) {
        pendingAlerts.remove(playerUUID);
        saveAlerts();
    }

    /**
     * Save alerts to file
     */
    public void saveAlerts() {
        alertsConfig = new YamlConfiguration();

        for (Map.Entry<UUID, List<String>> entry : pendingAlerts.entrySet()) {
            String path = "alerts." + entry.getKey().toString();
            alertsConfig.set(path, entry.getValue());
        }

        try {
            plugin.getDataFolder().mkdirs();
            alertsConfig.save(alertsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save alerts: " + e.getMessage());
        }
    }

    /**
     * Load alerts from file
     */
    public void loadAlerts() {
        if (!alertsFile.exists()) {
            return;
        }

        alertsConfig = YamlConfiguration.loadConfiguration(alertsFile);

        if (alertsConfig.getConfigurationSection("alerts") == null) {
            return;
        }

        for (String uuidString : alertsConfig.getConfigurationSection("alerts").getKeys(false)) {
            try {
                UUID playerUUID = UUID.fromString(uuidString);
                List<String> alerts = alertsConfig.getStringList("alerts." + uuidString);
                if (!alerts.isEmpty()) {
                    pendingAlerts.put(playerUUID, new ArrayList<>(alerts));
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in alerts file: " + uuidString);
            }
        }

        plugin.getLogger().info("Loaded " + pendingAlerts.size() + " pending alert queues.");
    }
}
