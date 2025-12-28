package com.chestshop.listeners;

import com.chestshop.ChestShopPlugin;
import com.chestshop.models.Shop;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.GameMode;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShopListener implements Listener {

    private final ChestShopPlugin plugin;
    private final Map<UUID, Long> transactionCooldowns = new ConcurrentHashMap<>();
    private final Set<String> activeTransactions = Collections.synchronizedSet(new HashSet<>());
    private static final long COOLDOWN_MS = 500; // 500ms between transactions

    public ShopListener(ChestShopPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isOnCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long lastTransaction = transactionCooldowns.get(player.getUniqueId());
        if (lastTransaction != null && (now - lastTransaction) < COOLDOWN_MS) {
            return true;
        }
        transactionCooldowns.put(player.getUniqueId(), now);
        return false;
    }

    /**
     * Handle shop creation when a player creates a sign
     * 
     * Sign format:
     * Line 1: [Shop]
     * Line 2: B <price> : S <price>   (buy price : sell price, use 0 to disable)
     * Line 3: <amount> <item>
     * Line 4: (left blank - will show owner name)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {
        // Check if first line is [Shop]
        String firstLine = event.getLine(0);
        if (firstLine == null || !firstLine.equalsIgnoreCase("[Shop]")) {
            return;
        }

        Player player = event.getPlayer();
        Block signBlock = event.getBlock();

        // Check permission
        if (!player.hasPermission("chestshop.create")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to create shops!");
            event.setCancelled(true);
            return;
        }

        // Check if sign is attached to a chest
        Block chestBlock = getAttachedChest(signBlock);
        if (chestBlock == null) {
            player.sendMessage(ChatColor.RED + "Shop sign must be placed on a chest!");
            event.setCancelled(true);
            return;
        }

        // Verify player has access to this chest (ownership check)
        if (!canPlayerAccessChest(player, chestBlock)) {
            player.sendMessage(ChatColor.RED + "You don't have access to this chest!");
            event.setCancelled(true);
            return;
        }

        // Check for double chest conflicts - prevent multiple shops on same double chest
        if (hasExistingShopOnDoubleChest(chestBlock)) {
            player.sendMessage(ChatColor.RED + "A shop already exists on this chest!");
            event.setCancelled(true);
            return;
        }

        // Check shop limit per player (unless has bypass permission)
        int maxShops = plugin.getMaxShopsPerPlayer();
        if (maxShops > 0 && !player.hasPermission("chestshop.bypass.limit")) {
            int playerShopCount = plugin.getShopManager().getPlayerShopCount(player.getUniqueId());
            if (playerShopCount >= maxShops) {
                player.sendMessage(plugin.getMessage("max-shops-reached", "{max}", String.valueOf(maxShops)));
                event.setCancelled(true);
                return;
            }
        }

        // Parse prices from line 2: "B 10 : S 5" or "B 10" or "S 5"
        String priceLine = event.getLine(1);
        double buyPrice = 0;
        double sellPrice = 0;

        if (priceLine != null && !priceLine.trim().isEmpty()) {
            try {
                String[] parts = priceLine.toUpperCase().split(":");
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("B ")) {
                        buyPrice = Double.parseDouble(part.substring(2).trim());
                    } else if (part.startsWith("S ")) {
                        sellPrice = Double.parseDouble(part.substring(2).trim());
                    }
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price format! Use: B <price> : S <price>");
                event.setCancelled(true);
                return;
            }
        }

        // Validate prices are not NaN or Infinity
        if (Double.isNaN(buyPrice) || Double.isNaN(sellPrice) ||
            Double.isInfinite(buyPrice) || Double.isInfinite(sellPrice)) {
            player.sendMessage(ChatColor.RED + "Invalid price value!");
            event.setCancelled(true);
            return;
        }

        // Validate prices are not negative (security fix: prevent negative price exploit)
        if (buyPrice < 0 || sellPrice < 0) {
            player.sendMessage(ChatColor.RED + "Prices cannot be negative!");
            event.setCancelled(true);
            return;
        }

        // Validate prices are not too large (prevent overflow issues)
        double maxPrice = plugin.getMaxPrice();
        if (buyPrice > maxPrice || sellPrice > maxPrice) {
            player.sendMessage(plugin.getMessage("price-too-high", "{max}", String.format("%,.0f", maxPrice)));
            event.setCancelled(true);
            return;
        }

        if (buyPrice <= 0 && sellPrice <= 0) {
            player.sendMessage(ChatColor.RED + "You must set at least a buy or sell price!");
            event.setCancelled(true);
            return;
        }

        // Parse amount and item from line 3: "64 Diamond" or "64 DIAMOND"
        String itemLine = event.getLine(2);
        int amount = 1;
        Material item = null;

        if (itemLine != null && !itemLine.trim().isEmpty()) {
            try {
                String[] parts = itemLine.trim().split(" ", 2);
                if (parts.length >= 2) {
                    amount = Integer.parseInt(parts[0]);
                    String itemName = parts[1].toUpperCase().replace(" ", "_");
                    item = Material.matchMaterial(itemName);
                } else {
                    // Try to parse as just an item name
                    String itemName = parts[0].toUpperCase().replace(" ", "_");
                    item = Material.matchMaterial(itemName);
                }
            } catch (NumberFormatException e) {
                // First part wasn't a number, try whole line as item name
                String itemName = itemLine.toUpperCase().replace(" ", "_");
                item = Material.matchMaterial(itemName);
            }
        }

        if (item == null) {
            player.sendMessage(ChatColor.RED + "Invalid item! Use: <amount> <item name>");
            event.setCancelled(true);
            return;
        }

        if (amount <= 0 || amount > 64) {
            player.sendMessage(ChatColor.RED + "Amount must be between 1 and 64!");
            event.setCancelled(true);
            return;
        }

        // Check if item is blocked (unless has bypass permission)
        if (plugin.isItemBlocked(item) && !player.hasPermission("chestshop.bypass.blockeditems")) {
            player.sendMessage(plugin.getMessage("item-blocked"));
            event.setCancelled(true);
            return;
        }

        // Check creation cost (unless has bypass permission)
        double creationCost = plugin.getShopCreationCost();
        if (creationCost > 0 && !player.hasPermission("chestshop.bypass.creationcost")) {
            Economy economy = plugin.getEconomy();
            if (!economy.has(player, creationCost)) {
                player.sendMessage(ChatColor.RED + "You need $" + String.format("%,.2f", creationCost) + " to create a shop!");
                event.setCancelled(true);
                return;
            }
            economy.withdrawPlayer(player, creationCost);
            player.sendMessage(plugin.getMessage("creation-cost", "{cost}", String.format("%,.2f", creationCost)));
        }

        // Create the shop
        Shop shop = new Shop(
                player.getUniqueId(),
                player.getName(),
                signBlock.getLocation(),
                chestBlock.getLocation(),
                item,
                amount,
                buyPrice,
                sellPrice
        );

        plugin.getShopManager().addShop(shop);

        // Create hologram if enabled
        if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
            plugin.getHologramManager().createHologram(shop);
        }

        // Update sign display - Clean & Modern style
        // Line 1: ✦ SHOP ✦ (gold)
        event.setLine(0, ChatColor.GOLD + "\u2726 " + ChatColor.BOLD + "SHOP " + ChatColor.RESET + ChatColor.GOLD + "\u2726");

        // Line 2: Item name (white, bold)
        event.setLine(1, ChatColor.WHITE + "" + ChatColor.BOLD + formatItemName(item));

        // Line 3: ⬆$price ⬇$price (green for buy, aqua for sell)
        String priceDisplay = "";
        if (buyPrice > 0) priceDisplay += ChatColor.GREEN + "\u2B06$" + formatPrice(buyPrice);
        if (buyPrice > 0 && sellPrice > 0) priceDisplay += " ";
        if (sellPrice > 0) priceDisplay += ChatColor.AQUA + "\u2B07$" + formatPrice(sellPrice);
        event.setLine(2, priceDisplay);

        // Line 4: Player name (gray)
        event.setLine(3, ChatColor.GRAY + player.getName());

        player.sendMessage(plugin.getMessage("shop-created"));
    }

    /**
     * Handle shop interaction (buying/selling)
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        Block block = event.getClickedBlock();
        if (!isSign(block.getType())) return;

        Shop shop = plugin.getShopManager().getShop(block.getLocation());
        if (shop == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();

        // Check if player has permission to use shops
        if (!player.hasPermission("chestshop.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use shops!");
            return;
        }

        // Block spectator and adventure mode
        if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE) {
            player.sendMessage(ChatColor.RED + "You cannot use shops in " + player.getGameMode().name().toLowerCase() + " mode!");
            return;
        }

        // Check cooldown to prevent rapid clicking exploit
        if (isOnCooldown(player)) {
            return;
        }

        // Don't let owners buy from their own shop
        if (player.getUniqueId().equals(shop.getOwnerUUID())) {
            player.sendMessage(ChatColor.YELLOW + "This is your shop!");
            return;
        }

        Block chestBlock = shop.getChestLocation().getBlock();
        if (!(chestBlock.getState() instanceof Chest chest)) {
            player.sendMessage(ChatColor.RED + "Shop chest not found!");
            return;
        }

        Inventory chestInventory = chest.getInventory();
        Economy economy = plugin.getEconomy();

        // Get unique shop key for transaction locking (with null safety)
        Location signLoc = shop.getSignLocation();
        String worldName = signLoc.getWorld() != null ? signLoc.getWorld().getName() : "unknown";
        String shopKey = worldName + ":" +
                signLoc.getBlockX() + ":" +
                signLoc.getBlockY() + ":" +
                signLoc.getBlockZ();

        // Prevent race condition - only one transaction per shop at a time
        if (!activeTransactions.add(shopKey)) {
            player.sendMessage(ChatColor.YELLOW + "Please wait, another transaction is in progress.");
            return;
        }

        try {
            // Right click = Buy, Left click = Sell
            switch (event.getAction()) {
                case RIGHT_CLICK_BLOCK -> {
                    // Player wants to BUY
                    if (!shop.canBuy()) {
                        player.sendMessage(ChatColor.RED + "This shop is not selling!");
                        return;
                    }
                    handleBuy(player, shop, chestInventory, economy);
                }
                case LEFT_CLICK_BLOCK -> {
                    // Player wants to SELL
                    if (!shop.canSell()) {
                        player.sendMessage(ChatColor.RED + "This shop is not buying!");
                        return;
                    }
                    handleSell(player, shop, chestInventory, economy);
                }
            }
        } finally {
            // Always release the transaction lock
            activeTransactions.remove(shopKey);
        }
    }

    private void handleBuy(Player player, Shop shop, Inventory chestInventory, Economy economy) {
        double price = shop.getBuyPrice();

        // Check if chest has enough items (matching exact item type)
        if (!hasEnoughItems(chestInventory, shop.getItem(), shop.getAmount())) {
            player.sendMessage(ChatColor.RED + "Shop is out of stock!");
            return;
        }

        // Check if player has enough money
        if (!economy.has(player, price)) {
            player.sendMessage(ChatColor.RED + "You don't have enough money! Need: $" + formatPrice(price));
            return;
        }

        // Check if player has inventory space
        ItemStack testItem = new ItemStack(shop.getItem(), shop.getAmount());
        if (player.getInventory().firstEmpty() == -1 && !canStackItem(player.getInventory(), testItem)) {
            player.sendMessage(ChatColor.RED + "Your inventory is full!");
            return;
        }

        // SAFE TRANSACTION ORDER: Items first, then money
        // Step 1: Collect actual items from chest (preserves enchantments/metadata)
        List<ItemStack> collectedItems = collectItems(chestInventory, shop.getItem(), shop.getAmount());
        if (collectedItems == null) {
            player.sendMessage(ChatColor.RED + "Transaction failed: Could not retrieve items.");
            return;
        }

        // Step 2: Add collected items to player (track what was successfully added for proper rollback)
        List<ItemStack> addedToPlayer = new ArrayList<>();
        boolean addFailed = false;

        for (ItemStack item : collectedItems) {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
            if (!leftover.isEmpty()) {
                // Partial add - some items couldn't fit
                // Calculate how many were actually added
                int addedAmount = item.getAmount();
                for (ItemStack left : leftover.values()) {
                    addedAmount -= left.getAmount();
                }
                if (addedAmount > 0) {
                    ItemStack added = item.clone();
                    added.setAmount(addedAmount);
                    addedToPlayer.add(added);
                }
                addFailed = true;
                break;
            } else {
                addedToPlayer.add(item.clone());
            }
        }

        if (addFailed) {
            // Rollback: Remove only what was actually added to player, return all to chest
            for (ItemStack added : addedToPlayer) {
                player.getInventory().removeItem(added);
            }
            for (ItemStack collected : collectedItems) {
                chestInventory.addItem(collected);
            }
            player.sendMessage(ChatColor.RED + "Transaction failed: Your inventory is full!");
            return;
        }

        // Step 3: Withdraw money from player
        EconomyResponse withdrawResponse = economy.withdrawPlayer(player, price);
        if (!withdrawResponse.transactionSuccess()) {
            // Rollback: Remove added items from player, return all to chest
            for (ItemStack item : addedToPlayer) {
                player.getInventory().removeItem(item);
            }
            for (ItemStack item : collectedItems) {
                chestInventory.addItem(item);
            }
            player.sendMessage(ChatColor.RED + "Transaction failed: " + withdrawResponse.errorMessage);
            return;
        }

        // Step 4: Deposit to shop owner (minus tax)
        double taxPercent = plugin.getTransactionTaxPercent();
        double taxAmount = price * (taxPercent / 100.0);
        double ownerReceives = price - taxAmount;

        EconomyResponse depositResponse = economy.depositPlayer(Bukkit(shop.getOwnerUUID()), ownerReceives);
        if (!depositResponse.transactionSuccess()) {
            // Critical: Refund player, remove items from player, return to chest
            economy.depositPlayer(player, price);
            for (ItemStack item : addedToPlayer) {
                player.getInventory().removeItem(item);
            }
            for (ItemStack item : collectedItems) {
                chestInventory.addItem(item);
            }
            player.sendMessage(ChatColor.RED + "Transaction failed: Could not pay shop owner.");
            plugin.getLogger().warning("Deposit failed for shop owner " + shop.getOwnerName() + ": " + depositResponse.errorMessage);
            return;
        }

        // Log transaction
        String taxInfo = taxAmount > 0 ? " (tax: $" + formatPrice(taxAmount) + ")" : "";
        plugin.getLogger().info("[Transaction] " + player.getName() + " bought " + shop.getAmount() + "x " +
                shop.getItem().name() + " from " + shop.getOwnerName() + " for $" + formatPrice(price) + taxInfo);

        player.sendMessage(plugin.getMessage("purchase-success",
                "{amount}", String.valueOf(shop.getAmount()),
                "{item}", formatItemName(shop.getItem()),
                "{price}", formatPrice(price)));

        // Check stock levels and send alerts
        if (plugin.isAlertsEnabled()) {
            checkStockAlerts(shop, chestInventory);
        }
    }

    private void handleSell(Player player, Shop shop, Inventory chestInventory, Economy economy) {
        double price = shop.getSellPrice();

        // Check if player has the items (matching exact item type)
        if (!hasEnoughItems(player.getInventory(), shop.getItem(), shop.getAmount())) {
            player.sendMessage(ChatColor.RED + "You don't have enough items to sell!");
            return;
        }

        // Check if chest has space
        ItemStack testItem = new ItemStack(shop.getItem(), shop.getAmount());
        if (chestInventory.firstEmpty() == -1 && !canStackItem(chestInventory, testItem)) {
            player.sendMessage(ChatColor.RED + "Shop chest is full!");
            return;
        }

        // Check if shop owner has enough money
        if (!economy.has(Bukkit(shop.getOwnerUUID()), price)) {
            player.sendMessage(ChatColor.RED + "Shop owner doesn't have enough money!");
            return;
        }

        // SAFE TRANSACTION ORDER: Items first, then money
        // Step 1: Collect actual items from player (preserves enchantments/metadata)
        List<ItemStack> collectedItems = collectItems(player.getInventory(), shop.getItem(), shop.getAmount());
        if (collectedItems == null) {
            player.sendMessage(ChatColor.RED + "Transaction failed: Could not take items.");
            return;
        }

        // Step 2: Add items to chest (track what was successfully added for proper rollback)
        List<ItemStack> addedToChest = new ArrayList<>();
        boolean addFailed = false;

        for (ItemStack item : collectedItems) {
            HashMap<Integer, ItemStack> leftover = chestInventory.addItem(item.clone());
            if (!leftover.isEmpty()) {
                // Partial add - some items couldn't fit
                int addedAmount = item.getAmount();
                for (ItemStack left : leftover.values()) {
                    addedAmount -= left.getAmount();
                }
                if (addedAmount > 0) {
                    ItemStack added = item.clone();
                    added.setAmount(addedAmount);
                    addedToChest.add(added);
                }
                addFailed = true;
                break;
            } else {
                addedToChest.add(item.clone());
            }
        }

        if (addFailed) {
            // Rollback: Remove only what was actually added to chest, return all to player
            for (ItemStack added : addedToChest) {
                chestInventory.removeItem(added);
            }
            for (ItemStack collected : collectedItems) {
                player.getInventory().addItem(collected);
            }
            player.sendMessage(ChatColor.RED + "Transaction failed: Shop chest is full!");
            return;
        }

        // Step 3: Withdraw money from shop owner
        EconomyResponse withdrawResponse = economy.withdrawPlayer(Bukkit(shop.getOwnerUUID()), price);
        if (!withdrawResponse.transactionSuccess()) {
            // Rollback: Remove added items from chest, return all to player
            for (ItemStack item : addedToChest) {
                chestInventory.removeItem(item);
            }
            for (ItemStack item : collectedItems) {
                player.getInventory().addItem(item);
            }
            player.sendMessage(ChatColor.RED + "Transaction failed: Owner has insufficient funds.");
            return;
        }

        // Step 4: Deposit to player (minus tax)
        double taxPercent = plugin.getTransactionTaxPercent();
        double taxAmount = price * (taxPercent / 100.0);
        double playerReceives = price - taxAmount;

        EconomyResponse depositResponse = economy.depositPlayer(player, playerReceives);
        if (!depositResponse.transactionSuccess()) {
            // Critical: Refund owner, remove items from chest, return to player
            economy.depositPlayer(Bukkit(shop.getOwnerUUID()), price);
            for (ItemStack item : addedToChest) {
                chestInventory.removeItem(item);
            }
            for (ItemStack item : collectedItems) {
                player.getInventory().addItem(item);
            }
            player.sendMessage(ChatColor.RED + "Transaction failed: Could not receive payment.");
            plugin.getLogger().warning("Deposit failed for player " + player.getName() + ": " + depositResponse.errorMessage);
            return;
        }

        // Log transaction
        String taxInfo = taxAmount > 0 ? " (tax: $" + formatPrice(taxAmount) + ")" : "";
        plugin.getLogger().info("[Transaction] " + player.getName() + " sold " + shop.getAmount() + "x " +
                shop.getItem().name() + " to " + shop.getOwnerName() + " for $" + formatPrice(playerReceives) + taxInfo);

        player.sendMessage(plugin.getMessage("sale-success",
                "{amount}", String.valueOf(shop.getAmount()),
                "{item}", formatItemName(shop.getItem()),
                "{price}", formatPrice(playerReceives)));

        // Check money levels and send alerts (chest might be full after sale)
        if (plugin.isAlertsEnabled()) {
            checkMoneyAlerts(shop, economy);
            checkChestFullAlert(shop, chestInventory);
        }
    }

    // Helper to get OfflinePlayer for economy transactions
    private org.bukkit.OfflinePlayer Bukkit(java.util.UUID uuid) {
        return org.bukkit.Bukkit.getOfflinePlayer(uuid);
    }

    /**
     * Protect shop signs and chests from being broken by non-owners
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        // Check if breaking a shop sign
        if (isSign(block.getType())) {
            Shop shop = plugin.getShopManager().getShop(block.getLocation());
            if (shop != null) {
                // Creative mode doesn't bypass protection unless admin
                boolean isOwner = player.getUniqueId().equals(shop.getOwnerUUID());
                boolean isAdmin = player.hasPermission("chestshop.admin");

                if (!isOwner && !isAdmin) {
                    player.sendMessage(ChatColor.RED + "You cannot break someone else's shop!");
                    event.setCancelled(true);
                    return;
                }

                // Warn creative mode players
                if (player.getGameMode() == GameMode.CREATIVE && !isAdmin) {
                    player.sendMessage(ChatColor.YELLOW + "Warning: Breaking shop in creative mode.");
                }

                // Owner is breaking their shop - remove hologram first
                if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
                    plugin.getHologramManager().removeHologram(shop);
                }
                plugin.getShopManager().removeShop(block.getLocation());
                player.sendMessage(plugin.getMessage("shop-removed"));
            }
        }

        // Check if breaking a shop chest
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            Shop shop = plugin.getShopManager().getShopByChest(block.getLocation());
            if (shop != null) {
                boolean isOwner = player.getUniqueId().equals(shop.getOwnerUUID());
                boolean isAdmin = player.hasPermission("chestshop.admin");

                if (!isOwner && !isAdmin) {
                    player.sendMessage(ChatColor.RED + "You cannot break a shop chest!");
                    event.setCancelled(true);
                } else {
                    // Owner or admin is breaking the chest - remove the shop
                    // Remove hologram first
                    if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
                        plugin.getHologramManager().removeHologram(shop);
                    }
                    // Also remove the sign
                    Location signLoc = shop.getSignLocation();
                    Block signBlock = signLoc.getBlock();
                    if (isSign(signBlock.getType())) {
                        signBlock.setType(Material.AIR);
                    }
                    plugin.getShopManager().removeShop(signLoc);
                    player.sendMessage(plugin.getMessage("shop-removed"));
                }
            }
        }
    }

    /**
     * Protect shop signs from fire damage
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        if (isSign(block.getType())) {
            if (plugin.getShopManager().getShop(block.getLocation()) != null) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Protect shop signs and chests from entity explosions (creepers, TNT, etc.)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectFromExplosion(event.blockList());
    }

    /**
     * Protect shop signs and chests from block explosions (beds in nether, respawn anchors)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectFromExplosion(event.blockList());
    }

    /**
     * Protect shop signs and chests from Wither and other entity block changes
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (isShopBlock(block)) {
            event.setCancelled(true);
        }
    }

    /**
     * Remove shop blocks from explosion block list to protect them
     */
    private void protectFromExplosion(List<Block> blocks) {
        Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();

            // Protect shop signs
            if (isSign(block.getType())) {
                Shop shop = plugin.getShopManager().getShop(block.getLocation());
                if (shop != null) {
                    iterator.remove();
                    continue;
                }
            }

            // Protect shop chests
            if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
                Shop shop = plugin.getShopManager().getShopByChest(block.getLocation());
                if (shop != null) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Clean up cooldown entries when players leave (prevents memory leak)
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        transactionCooldowns.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Prevent hoppers (including minecart hoppers) from stealing items from shop chests
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        InventoryHolder sourceHolder = event.getSource().getHolder();

        // Check if source is a shop chest (items being pulled out)
        if (sourceHolder instanceof Chest chest) {
            if (plugin.getShopManager().getShopByChest(chest.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
        }

        // Check double chests
        if (sourceHolder instanceof DoubleChest doubleChest) {
            Chest leftChest = (Chest) doubleChest.getLeftSide();
            Chest rightChest = (Chest) doubleChest.getRightSide();
            if (leftChest != null && plugin.getShopManager().getShopByChest(leftChest.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
            if (rightChest != null && plugin.getShopManager().getShopByChest(rightChest.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
        }

        // Also prevent items being pushed INTO shop chests by hoppers (could overflow)
        InventoryHolder destHolder = event.getDestination().getHolder();
        if (destHolder instanceof Chest chest) {
            if (plugin.getShopManager().getShopByChest(chest.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
        }
        if (destHolder instanceof DoubleChest doubleChest) {
            Chest leftChest = (Chest) doubleChest.getLeftSide();
            Chest rightChest = (Chest) doubleChest.getRightSide();
            if (leftChest != null && plugin.getShopManager().getShopByChest(leftChest.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
            if (rightChest != null && plugin.getShopManager().getShopByChest(rightChest.getLocation()) != null) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Prevent pistons from pushing shop chests or signs
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isShopBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Prevent pistons from pulling shop chests or signs
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isShopBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Prevent water/lava from breaking shop signs
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        // Check if water/lava is flowing into a shop sign
        if (isSign(toBlock.getType())) {
            if (plugin.getShopManager().getShop(toBlock.getLocation()) != null) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Prevent shop signs from being detached when their supporting block is broken
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        // Check if this is a shop sign that's about to be detached
        if (isSign(block.getType())) {
            Shop shop = plugin.getShopManager().getShop(block.getLocation());
            if (shop != null) {
                // Check if the sign would fall (no longer has valid attachment)
                Block attachedChest = getAttachedChest(block);
                if (attachedChest == null) {
                    // Sign lost its attachment - remove hologram and shop
                    if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
                        plugin.getHologramManager().removeHologram(shop);
                    }
                    plugin.getShopManager().removeShop(block.getLocation());
                    plugin.getLogger().info("Shop sign at " + block.getLocation() + " was detached, removing shop.");
                }
            }
        }
    }

    /**
     * Check if a block is part of a shop (sign or chest)
     */
    private boolean isShopBlock(Block block) {
        if (isSign(block.getType())) {
            return plugin.getShopManager().getShop(block.getLocation()) != null;
        }
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            return plugin.getShopManager().getShopByChest(block.getLocation()) != null;
        }
        return false;
    }

    /**
     * Get the chest that a sign is attached to
     */
    private Block getAttachedChest(Block signBlock) {
        // Check if it's a wall sign
        if (signBlock.getBlockData() instanceof WallSign wallSign) {
            BlockFace facing = wallSign.getFacing();
            Block attachedBlock = signBlock.getRelative(facing.getOppositeFace());
            if (attachedBlock.getType() == Material.CHEST || attachedBlock.getType() == Material.TRAPPED_CHEST) {
                return attachedBlock;
            }
        }

        // Check block below for standing signs
        Block below = signBlock.getRelative(BlockFace.DOWN);
        if (below.getType() == Material.CHEST || below.getType() == Material.TRAPPED_CHEST) {
            return below;
        }

        return null;
    }

    private boolean isSign(Material material) {
        // More specific check to avoid matching unintended materials
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") ||
               name.equals("SIGN") || name.endsWith("_HANGING_SIGN") ||
               name.endsWith("_WALL_HANGING_SIGN");
    }

    /**
     * Collect actual items from inventory (preserves enchantments/metadata)
     * Returns list of collected ItemStacks, or null if not enough items
     */
    private List<ItemStack> collectItems(Inventory inventory, Material material, int amount) {
        List<ItemStack> collected = new ArrayList<>();
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == material) {
                int stackAmount = stack.getAmount();
                if (stackAmount <= remaining) {
                    // Take the whole stack
                    collected.add(stack.clone());
                    inventory.setItem(i, null);
                    remaining -= stackAmount;
                } else {
                    // Take partial stack
                    ItemStack taken = stack.clone();
                    taken.setAmount(remaining);
                    collected.add(taken);
                    stack.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
            }
        }

        if (remaining > 0) {
            // Not enough items - rollback what we collected
            for (ItemStack item : collected) {
                inventory.addItem(item);
            }
            return null;
        }

        return collected;
    }

    private boolean canStackItem(Inventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();

        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == item.getType()) {
                int spaceInStack = maxStack - stack.getAmount();
                remaining -= spaceInStack;
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if inventory has enough of a specific item type (ignores metadata)
     */
    private boolean hasEnoughItems(Inventory inventory, Material material, int amount) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
                if (count >= amount) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove a specific amount of items by material type (ignores metadata)
     * Returns true if successful, false if not enough items
     */
    private boolean removeItems(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == material) {
                int stackAmount = stack.getAmount();
                if (stackAmount <= remaining) {
                    remaining -= stackAmount;
                    inventory.setItem(i, null);
                } else {
                    stack.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
            }
        }

        return remaining == 0;
    }

    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.format("%d", (long) price);
        }
        return String.format("%.2f", price);
    }

    // Cache for formatted item names to avoid repeated string operations
    private static final Map<Material, String> itemNameCache = new HashMap<>();

    private String formatItemName(Material material) {
        return itemNameCache.computeIfAbsent(material, m -> {
            String name = m.name().toLowerCase().replace("_", " ");
            String[] words = name.split(" ");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (!word.isEmpty()) {
                    result.append(Character.toUpperCase(word.charAt(0)))
                            .append(word.substring(1)).append(" ");
                }
            }
            return result.toString().trim();
        });
    }

    /**
     * Check if a shop already exists on a double chest
     * This prevents multiple shops on the same inventory
     */
    private boolean hasExistingShopOnDoubleChest(Block chestBlock) {
        if (!(chestBlock.getState() instanceof Chest chest)) {
            return false;
        }

        // Check if this exact chest already has a shop
        if (plugin.getShopManager().getShopByChest(chestBlock.getLocation()) != null) {
            return true;
        }

        // Check if it's a double chest and the other half has a shop
        Inventory inventory = chest.getInventory();
        if (inventory.getHolder() instanceof DoubleChest doubleChest) {
            Chest leftChest = (Chest) doubleChest.getLeftSide();
            Chest rightChest = (Chest) doubleChest.getRightSide();

            if (leftChest != null && plugin.getShopManager().getShopByChest(leftChest.getLocation()) != null) {
                return true;
            }
            if (rightChest != null && plugin.getShopManager().getShopByChest(rightChest.getLocation()) != null) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a player has access to a chest (ownership/protection check)
     * This checks if the player could break the chest block, which respects
     * protection plugins like WorldGuard, GriefPrevention, Towny, etc.
     */
    private boolean canPlayerAccessChest(Player player, Block chestBlock) {
        // Admins can always access
        if (player.hasPermission("chestshop.admin")) {
            return true;
        }

        // Create a fake block break event to check if player can break this chest
        // This respects other protection plugins that listen to BlockBreakEvent
        BlockBreakEvent testEvent = new BlockBreakEvent(chestBlock, player);
        plugin.getServer().getPluginManager().callEvent(testEvent);

        // If the event was cancelled, the player doesn't have access
        return !testEvent.isCancelled();
    }

    /**
     * Check stock levels and send alerts to shop owner
     */
    private void checkStockAlerts(Shop shop, Inventory chestInventory) {
        int remaining = countItems(chestInventory, shop.getItem());
        int threshold = plugin.getLowStockThreshold();
        String itemName = formatItemName(shop.getItem());
        String location = formatLocation(shop.getSignLocation());

        if (remaining == 0) {
            plugin.getAlertManager().sendOutOfStockAlert(
                    shop.getOwnerUUID(),
                    shop.getOwnerName(),
                    itemName,
                    location
            );
        } else if (remaining <= threshold) {
            plugin.getAlertManager().sendLowStockAlert(
                    shop.getOwnerUUID(),
                    shop.getOwnerName(),
                    itemName,
                    remaining,
                    location
            );
        }
    }

    /**
     * Check owner's balance and send alerts for buy-back shops
     */
    private void checkMoneyAlerts(Shop shop, Economy economy) {
        if (!shop.canSell()) {
            return; // Only alert for shops that buy items
        }

        double ownerBalance = economy.getBalance(Bukkit(shop.getOwnerUUID()));
        double threshold = plugin.getLowMoneyThreshold();
        String itemName = formatItemName(shop.getItem());
        String location = formatLocation(shop.getSignLocation());

        if (ownerBalance < threshold) {
            plugin.getAlertManager().sendLowMoneyAlert(
                    shop.getOwnerUUID(),
                    shop.getOwnerName(),
                    itemName,
                    ownerBalance,
                    location
            );
        }
    }

    /**
     * Check if chest is full and send alert
     */
    private void checkChestFullAlert(Shop shop, Inventory chestInventory) {
        if (chestInventory.firstEmpty() == -1) {
            // Chest is full - check if it can stack the shop item
            ItemStack testItem = new ItemStack(shop.getItem(), 1);
            if (!canStackItem(chestInventory, testItem)) {
                String itemName = formatItemName(shop.getItem());
                String location = formatLocation(shop.getSignLocation());
                plugin.getAlertManager().sendShopFullAlert(
                        shop.getOwnerUUID(),
                        shop.getOwnerName(),
                        itemName,
                        location
                );
            }
        }
    }

    /**
     * Count items of a specific material in an inventory
     */
    private int countItems(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Format location for display in alerts
     */
    private String formatLocation(Location loc) {
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        return worldName + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
