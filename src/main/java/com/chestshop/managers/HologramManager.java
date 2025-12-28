package com.chestshop.managers;

import com.chestshop.ChestShopPlugin;
import com.chestshop.models.Shop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages holograms above shop signs using DecentHolograms
 */
public class HologramManager {

    private final ChestShopPlugin plugin;
    private final Map<String, Object> holograms = new HashMap<>(); // Store hologram references
    private boolean decentHologramsEnabled = false;

    // DecentHolograms API classes (loaded via reflection to avoid hard dependency)
    private Class<?> dhApiClass;
    private Class<?> hologramClass;
    private Object dhApi;

    public HologramManager(ChestShopPlugin plugin) {
        this.plugin = plugin;
        setupDecentHolograms();
    }

    /**
     * Setup DecentHolograms integration
     */
    private void setupDecentHolograms() {
        if (Bukkit.getPluginManager().getPlugin("DecentHolograms") == null) {
            plugin.getLogger().info("DecentHolograms not found - holograms disabled.");
            return;
        }

        try {
            // Get the DecentHolograms API using reflection
            dhApiClass = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            hologramClass = Class.forName("eu.decentsoftware.holograms.api.holograms.Hologram");

            decentHologramsEnabled = true;
            plugin.getLogger().info("DecentHolograms integration enabled!");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("DecentHolograms found but API not accessible: " + e.getMessage());
        }
    }

    /**
     * Check if holograms are enabled
     */
    public boolean isEnabled() {
        return decentHologramsEnabled;
    }

    /**
     * Create a hologram for a shop
     */
    public void createHologram(Shop shop) {
        if (!decentHologramsEnabled) return;

        try {
            String hologramId = getHologramId(shop);
            Location chestLoc = shop.getChestLocation();

            // Position hologram above the chest (centered, 2.5 blocks up)
            Location holoLoc = chestLoc.clone().add(0.5, 2.5, 0.5);

            // Build hologram lines
            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add("&6&l" + formatItemName(shop.getItem()));
            lines.add("&7" + shop.getAmount() + "x");

            String priceLine = "";
            if (shop.canBuy()) {
                priceLine += "&a⬆$" + formatPrice(shop.getBuyPrice());
            }
            if (shop.canBuy() && shop.canSell()) {
                priceLine += " &7| ";
            }
            if (shop.canSell()) {
                priceLine += "&b⬇$" + formatPrice(shop.getSellPrice());
            }
            lines.add(priceLine);
            lines.add("&8" + shop.getOwnerName());

            // Create hologram using reflection
            java.lang.reflect.Method createMethod = dhApiClass.getMethod("createHologram",
                    String.class, Location.class, boolean.class, java.util.List.class);
            Object hologram = createMethod.invoke(null, hologramId, holoLoc, false, lines);

            holograms.put(hologramId, hologram);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create hologram: " + e.getMessage());
        }
    }

    /**
     * Remove a hologram for a shop
     */
    public void removeHologram(Shop shop) {
        if (!decentHologramsEnabled) return;

        try {
            String hologramId = getHologramId(shop);

            // Remove using reflection
            java.lang.reflect.Method removeMethod = dhApiClass.getMethod("removeHologram", String.class);
            removeMethod.invoke(null, hologramId);

            holograms.remove(hologramId);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove hologram: " + e.getMessage());
        }
    }

    /**
     * Remove hologram by location (used when shop is removed)
     */
    public void removeHologram(Location signLocation) {
        if (!decentHologramsEnabled) return;

        try {
            String hologramId = locationToId(signLocation);

            java.lang.reflect.Method removeMethod = dhApiClass.getMethod("removeHologram", String.class);
            removeMethod.invoke(null, hologramId);

            holograms.remove(hologramId);

        } catch (Exception e) {
            // Silently fail - hologram might not exist
        }
    }

    /**
     * Update a hologram (e.g., when stock changes)
     */
    public void updateHologram(Shop shop) {
        if (!decentHologramsEnabled) return;

        // Simple approach: remove and recreate
        removeHologram(shop);
        createHologram(shop);
    }

    /**
     * Remove all holograms (on plugin disable)
     */
    public void removeAllHolograms() {
        if (!decentHologramsEnabled) return;

        try {
            java.lang.reflect.Method removeMethod = dhApiClass.getMethod("removeHologram", String.class);

            for (String hologramId : new java.util.ArrayList<>(holograms.keySet())) {
                try {
                    removeMethod.invoke(null, hologramId);
                } catch (Exception ignored) {}
            }

            holograms.clear();

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove holograms: " + e.getMessage());
        }
    }

    /**
     * Create holograms for all existing shops
     */
    public void createAllHolograms() {
        if (!decentHologramsEnabled) return;

        int count = 0;
        for (Shop shop : plugin.getShopManager().getAllShops()) {
            createHologram(shop);
            count++;
        }

        if (count > 0) {
            plugin.getLogger().info("Created " + count + " shop holograms.");
        }
    }

    /**
     * Generate a unique ID for a shop hologram
     */
    private String getHologramId(Shop shop) {
        return locationToId(shop.getSignLocation());
    }

    private String locationToId(Location loc) {
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        return "chestshop_" + worldName + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    private String formatItemName(Material material) {
        String[] words = material.name().toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(" ");
            result.append(word.substring(0, 1).toUpperCase()).append(word.substring(1));
        }
        return result.toString();
    }

    private String formatPrice(double price) {
        if (price >= 1000000) {
            return String.format("%.1fM", price / 1000000);
        } else if (price >= 1000) {
            return String.format("%.1fK", price / 1000);
        } else if (price == (int) price) {
            return String.valueOf((int) price);
        }
        return String.format("%.2f", price);
    }
}
