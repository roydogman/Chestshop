package com.chestshop.managers;

import com.chestshop.ChestShopPlugin;
import com.chestshop.models.Shop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShopManager {

    private final ChestShopPlugin plugin;
    private final Map<String, Shop> shops;
    private final Map<String, Shop> chestIndex; // Secondary index for O(1) chest lookup
    private final Map<Material, Set<Shop>> itemIndex; // Index for O(1) item lookups
    private final Map<UUID, Set<Shop>> playerIndex; // Index for O(1) player shop count
    private final File shopsFile;
    private final File backupFile;
    private FileConfiguration shopsConfig;
    private final Object saveLock = new Object(); // Lock for synchronized saves

    // Async/batch save system
    private volatile boolean dirty = false; // Flag to track if data needs saving
    private int autoSaveTaskId = -1;
    private static final long AUTO_SAVE_INTERVAL = 5 * 60 * 20L; // 5 minutes in ticks (20 ticks = 1 second)

    public ShopManager(ChestShopPlugin plugin) {
        this.plugin = plugin;
        this.shops = new HashMap<>();
        this.chestIndex = new HashMap<>();
        this.itemIndex = new HashMap<>();
        this.playerIndex = new HashMap<>();
        this.shopsFile = new File(plugin.getDataFolder(), "shops.yml");
        this.backupFile = new File(plugin.getDataFolder(), "shops.yml.backup");
        loadShops();
        startAutoSave();
    }

    private String locationToKey(Location location) {
        if (location == null) {
            return "null:0:0:0";
        }
        World world = location.getWorld();
        String worldName = world != null ? world.getName() : "unknown";
        return worldName + ":" +
               location.getBlockX() + ":" +
               location.getBlockY() + ":" +
               location.getBlockZ();
    }

    public void addShop(Shop shop) {
        String signKey = locationToKey(shop.getSignLocation());
        String chestKey = locationToKey(shop.getChestLocation());
        shops.put(signKey, shop);
        chestIndex.put(chestKey, shop);
        // Add to item index
        itemIndex.computeIfAbsent(shop.getItem(), k -> new HashSet<>()).add(shop);
        // Add to player index
        playerIndex.computeIfAbsent(shop.getOwnerUUID(), k -> new HashSet<>()).add(shop);
        markDirty(); // Mark for saving instead of saving immediately
    }

    public void removeShop(Location signLocation) {
        String signKey = locationToKey(signLocation);
        Shop shop = shops.remove(signKey);
        if (shop != null) {
            chestIndex.remove(locationToKey(shop.getChestLocation()));
            // Remove from item index
            Set<Shop> itemShops = itemIndex.get(shop.getItem());
            if (itemShops != null) {
                itemShops.remove(shop);
                if (itemShops.isEmpty()) {
                    itemIndex.remove(shop.getItem());
                }
            }
            // Remove from player index
            Set<Shop> playerShops = playerIndex.get(shop.getOwnerUUID());
            if (playerShops != null) {
                playerShops.remove(shop);
                if (playerShops.isEmpty()) {
                    playerIndex.remove(shop.getOwnerUUID());
                }
            }
        }
        markDirty(); // Mark for saving instead of saving immediately
    }

    /**
     * Mark data as changed - will be saved on next auto-save cycle
     */
    private void markDirty() {
        this.dirty = true;
    }

    public Shop getShop(Location signLocation) {
        return shops.get(locationToKey(signLocation));
    }

    public Shop getShopByChest(Location chestLocation) {
        // O(1) lookup using secondary index
        return chestIndex.get(locationToKey(chestLocation));
    }

    public boolean isShopSign(Location location) {
        return shops.containsKey(locationToKey(location));
    }

    public boolean isShopChest(Location location) {
        return getShopByChest(location) != null;
    }

    public Collection<Shop> getAllShops() {
        // Return a copy to prevent ConcurrentModificationException and external modification
        return new ArrayList<>(shops.values());
    }

    public List<Shop> getShopsByItem(Material item) {
        // O(1) lookup using item index
        Set<Shop> itemShops = itemIndex.get(item);
        if (itemShops == null || itemShops.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(itemShops);
    }

    /**
     * Get the number of shops owned by a player - O(1) lookup
     */
    public int getPlayerShopCount(UUID playerUUID) {
        Set<Shop> playerShops = playerIndex.get(playerUUID);
        return playerShops != null ? playerShops.size() : 0;
    }

    public void reloadShops() {
        shops.clear();
        chestIndex.clear();
        itemIndex.clear();
        playerIndex.clear();
        loadShops();
    }

    public void saveShops() {
        synchronized (saveLock) {
            shopsConfig = new YamlConfiguration();

            int index = 0;
            for (Shop shop : shops.values()) {
                String path = "shops." + index;
                shopsConfig.set(path + ".owner-uuid", shop.getOwnerUUID().toString());
                shopsConfig.set(path + ".owner-name", shop.getOwnerName());

                // Safe world name retrieval
                Location signLoc = shop.getSignLocation();
                Location chestLoc = shop.getChestLocation();
                String signWorld = signLoc.getWorld() != null ? signLoc.getWorld().getName() : "world";
                String chestWorld = chestLoc.getWorld() != null ? chestLoc.getWorld().getName() : "world";

                shopsConfig.set(path + ".sign-world", signWorld);
                shopsConfig.set(path + ".sign-x", signLoc.getBlockX());
                shopsConfig.set(path + ".sign-y", signLoc.getBlockY());
                shopsConfig.set(path + ".sign-z", signLoc.getBlockZ());
                shopsConfig.set(path + ".chest-world", chestWorld);
                shopsConfig.set(path + ".chest-x", chestLoc.getBlockX());
                shopsConfig.set(path + ".chest-y", chestLoc.getBlockY());
                shopsConfig.set(path + ".chest-z", chestLoc.getBlockZ());
                shopsConfig.set(path + ".item", shop.getItem().name());
                shopsConfig.set(path + ".amount", shop.getAmount());
                shopsConfig.set(path + ".buy-price", shop.getBuyPrice());
                shopsConfig.set(path + ".sell-price", shop.getSellPrice());
                index++;
            }

            try {
                // Create backup before saving
                if (shopsFile.exists()) {
                    Files.copy(shopsFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                // Ensure data folder exists
                plugin.getDataFolder().mkdirs();

                // Save the file
                shopsConfig.save(shopsFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save shops: " + e.getMessage());
                // Try to restore from backup
                if (backupFile.exists()) {
                    plugin.getLogger().warning("Attempting to restore from backup...");
                    try {
                        Files.copy(backupFile.toPath(), shopsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        plugin.getLogger().severe("Failed to restore backup: " + ex.getMessage());
                    }
                }
            }
        }
    }

    public void loadShops() {
        if (!shopsFile.exists()) {
            plugin.getDataFolder().mkdirs();
            return;
        }

        shopsConfig = YamlConfiguration.loadConfiguration(shopsFile);
        ConfigurationSection shopsSection = shopsConfig.getConfigurationSection("shops");

        if (shopsSection == null) {
            return;
        }

        int skippedShops = 0;
        for (String key : shopsSection.getKeys(false)) {
            String path = "shops." + key;

            try {
                // Safe null checks for all string values
                String uuidString = shopsConfig.getString(path + ".owner-uuid");
                if (uuidString == null || uuidString.isEmpty()) {
                    plugin.getLogger().warning("Skipping shop " + key + ": missing owner-uuid");
                    skippedShops++;
                    continue;
                }
                UUID ownerUUID = UUID.fromString(uuidString);

                String ownerName = shopsConfig.getString(path + ".owner-name", "Unknown");

                // Get world names and validate they exist
                String signWorldName = shopsConfig.getString(path + ".sign-world");
                String chestWorldName = shopsConfig.getString(path + ".chest-world");

                if (signWorldName == null || chestWorldName == null) {
                    plugin.getLogger().warning("Skipping shop " + key + ": missing world name");
                    skippedShops++;
                    continue;
                }

                World signWorld = Bukkit.getWorld(signWorldName);
                World chestWorld = Bukkit.getWorld(chestWorldName);

                // Skip shops in unloaded/deleted worlds
                if (signWorld == null) {
                    plugin.getLogger().warning("Skipping shop " + key + ": world '" + signWorldName + "' not found");
                    skippedShops++;
                    continue;
                }
                if (chestWorld == null) {
                    plugin.getLogger().warning("Skipping shop " + key + ": world '" + chestWorldName + "' not found");
                    skippedShops++;
                    continue;
                }

                Location signLocation = new Location(
                        signWorld,
                        shopsConfig.getInt(path + ".sign-x"),
                        shopsConfig.getInt(path + ".sign-y"),
                        shopsConfig.getInt(path + ".sign-z")
                );

                Location chestLocation = new Location(
                        chestWorld,
                        shopsConfig.getInt(path + ".chest-x"),
                        shopsConfig.getInt(path + ".chest-y"),
                        shopsConfig.getInt(path + ".chest-z")
                );

                // Verify blocks still exist (optional integrity check)
                Block signBlock = signLocation.getBlock();
                Block chestBlock = chestLocation.getBlock();

                if (!isSign(signBlock.getType())) {
                    plugin.getLogger().warning("Skipping shop " + key + ": sign block no longer exists at " + signLocation);
                    skippedShops++;
                    continue;
                }

                if (chestBlock.getType() != Material.CHEST && chestBlock.getType() != Material.TRAPPED_CHEST) {
                    plugin.getLogger().warning("Skipping shop " + key + ": chest block no longer exists at " + chestLocation);
                    skippedShops++;
                    continue;
                }

                // Safe Material parsing with fallback
                String itemName = shopsConfig.getString(path + ".item");
                Material item;
                try {
                    item = Material.valueOf(itemName);
                } catch (IllegalArgumentException | NullPointerException e) {
                    plugin.getLogger().warning("Skipping shop " + key + ": invalid item '" + itemName + "'");
                    skippedShops++;
                    continue;
                }

                int amount = shopsConfig.getInt(path + ".amount", 1);
                double buyPrice = shopsConfig.getDouble(path + ".buy-price", 0);
                double sellPrice = shopsConfig.getDouble(path + ".sell-price", 0);

                // Validate loaded prices (in case file was manually edited)
                if (buyPrice < 0) buyPrice = 0;
                if (sellPrice < 0) sellPrice = 0;
                if (amount <= 0 || amount > 64) amount = 1;

                Shop shop = new Shop(ownerUUID, ownerName, signLocation, chestLocation,
                        item, amount, buyPrice, sellPrice);

                // Add to all indexes
                shops.put(locationToKey(signLocation), shop);
                chestIndex.put(locationToKey(chestLocation), shop);
                itemIndex.computeIfAbsent(item, k -> new HashSet<>()).add(shop);
                playerIndex.computeIfAbsent(ownerUUID, k -> new HashSet<>()).add(shop);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load shop " + key + ": " + e.getMessage());
                skippedShops++;
            }
        }

        if (skippedShops > 0) {
            plugin.getLogger().warning("Skipped " + skippedShops + " shops due to errors.");
        }

        plugin.getLogger().info("Loaded " + shops.size() + " shops.");
    }

    /**
     * Check if a material is a sign type
     */
    private boolean isSign(Material material) {
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") ||
               name.equals("SIGN") || name.endsWith("_HANGING_SIGN") ||
               name.endsWith("_WALL_HANGING_SIGN");
    }

    /**
     * Start the auto-save task that runs every 5 minutes
     */
    public void startAutoSave() {
        if (autoSaveTaskId != -1) {
            return; // Already running
        }

        autoSaveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dirty) {
                saveShopsAsync();
            }
        }, AUTO_SAVE_INTERVAL, AUTO_SAVE_INTERVAL).getTaskId();

        plugin.getLogger().info("Auto-save started (every 5 minutes)");
    }

    /**
     * Stop the auto-save task
     */
    public void stopAutoSave() {
        if (autoSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoSaveTaskId);
            autoSaveTaskId = -1;
        }
    }

    /**
     * Save shops asynchronously (doesn't freeze the server)
     */
    public void saveShopsAsync() {
        // Take a snapshot of the data on the main thread context
        final List<Shop> shopSnapshot = new ArrayList<>(shops.values());
        dirty = false; // Reset flag before saving

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (saveLock) {
                YamlConfiguration config = new YamlConfiguration();

                int index = 0;
                for (Shop shop : shopSnapshot) {
                    String path = "shops." + index;
                    config.set(path + ".owner-uuid", shop.getOwnerUUID().toString());
                    config.set(path + ".owner-name", shop.getOwnerName());

                    Location signLoc = shop.getSignLocation();
                    Location chestLoc = shop.getChestLocation();
                    String signWorld = signLoc.getWorld() != null ? signLoc.getWorld().getName() : "world";
                    String chestWorld = chestLoc.getWorld() != null ? chestLoc.getWorld().getName() : "world";

                    config.set(path + ".sign-world", signWorld);
                    config.set(path + ".sign-x", signLoc.getBlockX());
                    config.set(path + ".sign-y", signLoc.getBlockY());
                    config.set(path + ".sign-z", signLoc.getBlockZ());
                    config.set(path + ".chest-world", chestWorld);
                    config.set(path + ".chest-x", chestLoc.getBlockX());
                    config.set(path + ".chest-y", chestLoc.getBlockY());
                    config.set(path + ".chest-z", chestLoc.getBlockZ());
                    config.set(path + ".item", shop.getItem().name());
                    config.set(path + ".amount", shop.getAmount());
                    config.set(path + ".buy-price", shop.getBuyPrice());
                    config.set(path + ".sell-price", shop.getSellPrice());
                    index++;
                }

                try {
                    if (shopsFile.exists()) {
                        Files.copy(shopsFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    plugin.getDataFolder().mkdirs();
                    config.save(shopsFile);
                    plugin.getLogger().info("Auto-saved " + shopSnapshot.size() + " shops.");
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to auto-save shops: " + e.getMessage());
                    dirty = true; // Mark dirty again so we retry next cycle
                }
            }
        });
    }

    /**
     * Force an immediate save (used on shutdown)
     */
    public void saveNow() {
        stopAutoSave();
        if (dirty || !shops.isEmpty()) {
            saveShops(); // Synchronous save
            plugin.getLogger().info("Shops saved on shutdown.");
        }
    }
}
