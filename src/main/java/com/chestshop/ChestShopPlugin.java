package com.chestshop;

import com.chestshop.commands.ShopCommand;
import com.chestshop.listeners.PlayerJoinListener;
import com.chestshop.listeners.ShopListener;
import com.chestshop.managers.AlertManager;
import com.chestshop.managers.HologramManager;
import com.chestshop.managers.ShopManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChestShopPlugin extends JavaPlugin {

    private static ChestShopPlugin instance;
    private Economy economy;
    private ShopManager shopManager;
    private AlertManager alertManager;
    private HologramManager hologramManager;
    private BukkitAudiences adventure;

    // Cached config values
    private int maxShopsPerPlayer;
    private double maxPrice;
    private double shopCreationCost;
    private double transactionTaxPercent;
    private Set<Material> blockedItems;

    // Alert settings
    private boolean alertsEnabled;
    private int lowStockThreshold;
    private double lowMoneyThreshold;
    private boolean hologramsEnabled;

    @Override
    public void onEnable() {
        instance = this;

        // Load configuration
        saveDefaultConfig();
        loadConfigValues();

        // Initialize Adventure
        this.adventure = BukkitAudiences.create(this);

        // Setup Vault economy
        if (!setupEconomy()) {
            getLogger().severe("Vault not found or no economy plugin installed!");
            getLogger().severe("Please install Vault and an economy plugin (like EssentialsX).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize managers
        shopManager = new ShopManager(this);
        alertManager = new AlertManager(this);
        hologramManager = new HologramManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new ShopListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        // Create holograms for existing shops (delayed to ensure world is loaded)
        if (hologramsEnabled && hologramManager.isEnabled()) {
            getServer().getScheduler().runTaskLater(this, () -> {
                hologramManager.createAllHolograms();
            }, 40L); // 2 second delay
        }

        // Register commands
        ShopCommand shopCommand = new ShopCommand(this);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);

        // Check for protection plugins
        String protectionPlugin = detectProtectionPlugin();

        // Startup message
        int shopCount = shopManager.getAllShops().size();
        getLogger().info("");
        getLogger().info("  _____ _               _   _____ _                 ");
        getLogger().info(" / ____| |             | | / ____| |                ");
        getLogger().info("| |    | |__   ___  ___| |_| (___ | |__   ___  _ __ ");
        getLogger().info("| |    | '_ \\ / _ \\/ __| __|\\___ \\| '_ \\ / _ \\| '_ \\");
        getLogger().info("| |____| | | |  __/\\__ \\ |_ ____) | | | | (_) | |_) |");
        getLogger().info(" \\_____|_| |_|\\___||___/\\__|_____/|_| |_|\\___/| .__/");
        getLogger().info("                                              | |   ");
        getLogger().info("                                              |_|   ");
        getLogger().info("");
        getLogger().info("  Version: " + getDescription().getVersion());
        List<String> authors = getDescription().getAuthors();
        getLogger().info("  Author: " + (authors.isEmpty() ? "Unknown" : authors.get(0)));
        getLogger().info("  Economy: " + economy.getName());
        getLogger().info("  Protection: " + (protectionPlugin != null ? protectionPlugin : "None"));
        getLogger().info("  Shops Loaded: " + shopCount);
        getLogger().info("");
        getLogger().info("  Plugin enabled successfully!");
        getLogger().info("");

        // Warning if no protection plugin detected
        if (protectionPlugin == null) {
            getLogger().warning("======================================================");
            getLogger().warning("  NO PROTECTION PLUGIN DETECTED!");
            getLogger().warning("  Players can create shops on ANY chest.");
            getLogger().warning("  Install WorldGuard, GriefPrevention, Towny, or");
            getLogger().warning("  another protection plugin to secure chests.");
            getLogger().warning("======================================================");
        }
    }

    /**
     * Detect if a protection plugin is installed
     */
    private String detectProtectionPlugin() {
        String[] protectionPlugins = {
            "WorldGuard", "GriefPrevention", "Towny", "Residence",
            "Factions", "Lands", "LWC", "Lockette", "BlockLocker",
            "ChestProtect", "Bolt"
        };

        for (String pluginName : protectionPlugins) {
            if (getServer().getPluginManager().getPlugin(pluginName) != null) {
                return pluginName;
            }
        }
        return null;
    }

    @Override
    public void onDisable() {
        // Remove holograms
        if (hologramManager != null) {
            hologramManager.removeAllHolograms();
        }
        // Save alerts
        if (alertManager != null) {
            alertManager.saveAlerts();
        }
        // Save shops
        if (shopManager != null) {
            shopManager.saveNow(); // Stop auto-save and do final save
        }
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
        getLogger().info("ChestShop has been disabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public static ChestShopPlugin getInstance() {
        return instance;
    }

    public Economy getEconomy() {
        return economy;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public BukkitAudiences getAdventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
        }
        return this.adventure;
    }

    private void loadConfigValues() {
        maxShopsPerPlayer = getConfig().getInt("max-shops-per-player", 50);
        maxPrice = getConfig().getDouble("max-price", 1_000_000_000);
        shopCreationCost = getConfig().getDouble("shop-creation-cost", 0);
        transactionTaxPercent = getConfig().getDouble("transaction-tax-percent", 0);

        // Clamp tax between 0 and 100
        if (transactionTaxPercent < 0) transactionTaxPercent = 0;
        if (transactionTaxPercent > 100) transactionTaxPercent = 100;

        // Load blocked items
        blockedItems = new HashSet<>();
        List<String> blockedList = getConfig().getStringList("blocked-items");
        for (String itemName : blockedList) {
            try {
                Material material = Material.valueOf(itemName.toUpperCase());
                blockedItems.add(material);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid blocked item in config: " + itemName);
            }
        }

        // Load alert settings
        alertsEnabled = getConfig().getBoolean("alerts.enabled", true);
        lowStockThreshold = getConfig().getInt("alerts.low-stock-threshold", 10);
        lowMoneyThreshold = getConfig().getDouble("alerts.low-money-threshold", 100);

        // Load hologram settings
        hologramsEnabled = getConfig().getBoolean("holograms.enabled", true);

        getLogger().info("Configuration loaded successfully.");
    }

    public void reloadConfiguration() {
        reloadConfig();
        loadConfigValues();
    }

    public int getMaxShopsPerPlayer() {
        return maxShopsPerPlayer;
    }

    public double getMaxPrice() {
        return maxPrice;
    }

    public double getShopCreationCost() {
        return shopCreationCost;
    }

    public double getTransactionTaxPercent() {
        return transactionTaxPercent;
    }

    public boolean isItemBlocked(Material material) {
        return blockedItems.contains(material);
    }

    public String getMessage(String key) {
        String message = getConfig().getString("messages." + key, "&cMissing message: " + key);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String getMessage(String key, Object... replacements) {
        String message = getMessage(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = String.valueOf(replacements[i]);
            String value = String.valueOf(replacements[i + 1]);
            message = message.replace(placeholder, value);
        }
        return message;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public HologramManager getHologramManager() {
        return hologramManager;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public double getLowMoneyThreshold() {
        return lowMoneyThreshold;
    }

    public boolean isHologramsEnabled() {
        return hologramsEnabled;
    }
}
