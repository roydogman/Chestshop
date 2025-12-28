package com.chestshop.listeners;

import com.chestshop.ChestShopPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Handles player join events for showing pending alerts
 */
public class PlayerJoinListener implements Listener {

    private final ChestShopPlugin plugin;

    public PlayerJoinListener(ChestShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Show pending alerts if alerts are enabled
        if (plugin.isAlertsEnabled() && plugin.getAlertManager() != null) {
            plugin.getAlertManager().showPendingAlerts(player);
        }
    }
}
