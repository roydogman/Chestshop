package com.chestshop.commands;

import com.chestshop.ChestShopPlugin;
import com.chestshop.managers.ShopManager;
import com.chestshop.models.Shop;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ShopCommand implements CommandExecutor, TabCompleter {

    private final ChestShopPlugin plugin;
    private final ShopManager shopManager;

    private Audience audience(CommandSender sender) {
        return plugin.getAdventure().sender(sender);
    }

    // Color scheme
    private static final TextColor PRIMARY = TextColor.color(0x55FFFF);      // Cyan
    private static final TextColor SECONDARY = TextColor.color(0xFFAA00);    // Gold
    private static final TextColor ACCENT = TextColor.color(0x55FF55);       // Green
    private static final TextColor ACCENT_ALT = TextColor.color(0xFF5555);   // Red
    private static final TextColor MUTED = TextColor.color(0xAAAAAA);        // Gray
    private static final TextColor HIGHLIGHT = TextColor.color(0xFFFF55);    // Yellow
    private static final TextColor BUY_COLOR = TextColor.color(0x00FF7F);    // Spring Green
    private static final TextColor SELL_COLOR = TextColor.color(0x00BFFF);   // Deep Sky Blue

    // UI Characters
    private static final String BORDER_TOP = "    ";
    private static final String BOX_TOP = "  +-----------------------------------------+";
    private static final String BOX_BOTTOM = "  +-----------------------------------------+";
    private static final String ARROW = "\u00BB";  // >>
    private static final String BULLET = "\u2022"; // bullet
    private static final String CHECK = "\u2714";  // checkmark
    private static final String CROSS = "\u2718";  // X
    private static final String SHOP_ICON = "\u26C8"; // umbrella with rain (shop-like)
    private static final String COIN = "\u25CF";   // filled circle (coin)
    private static final String STAR = "\u2605";   // star

    public ShopCommand(ChestShopPlugin plugin) {
        this.plugin = plugin;
        this.shopManager = plugin.getShopManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "help":
                showHelp(sender);
                break;
            case "create":
                if (!(sender instanceof Player player)) {
                    sendError(sender, "This command can only be used by players.");
                    return true;
                }
                handleCreate(player, args);
                break;
            case "find":
                handleFind(sender, args);
                break;
            case "info":
                if (!(sender instanceof Player player)) {
                    sendError(sender, "This command can only be used by players.");
                    return true;
                }
                handleInfo(player);
                break;
            case "remove":
                if (!(sender instanceof Player player)) {
                    sendError(sender, "This command can only be used by players.");
                    return true;
                }
                handleRemove(player);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                sendError(sender, "Unknown command. Use /shop help for available commands.");
                break;
        }

        return true;
    }

    private void showHelp(CommandSender sender) {
        Audience audience = audience(sender);
        audience.sendMessage(Component.empty());
        audience.sendMessage(createHeader("ChestShop Commands"));
        audience.sendMessage(Component.empty());

        sendCommandHelp(sender, "/shop create", "<item> <amt> <buy> <sell>", "Create a new shop", "Look at a chest and run this command\nUse 0 for buy/sell to disable");
        sendCommandHelp(sender, "/shop find", "<item>", "Search for shops", "Find all shops trading an item");
        sendCommandHelp(sender, "/shop info", "", "View shop details", "Look at a shop sign");
        sendCommandHelp(sender, "/shop remove", "", "Delete your shop", "Look at your shop sign");

        if (sender.hasPermission("chestshop.admin")) {
            audience.sendMessage(Component.empty());
            audience.sendMessage(Component.text("  Admin Commands", MUTED).decorate(TextDecoration.ITALIC));
            sendCommandHelp(sender, "/shop reload", "", "Reload from disk", "Reloads all shop data");
        }

        audience.sendMessage(Component.empty());
        audience.sendMessage(createFooter());
    }

    private void sendCommandHelp(CommandSender sender, String cmd, String args, String desc, String hover) {
        Component cmdComponent = Component.text("  " + ARROW + " ", MUTED)
                .append(Component.text(cmd, PRIMARY).decorate(TextDecoration.BOLD))
                .append(Component.text(" " + args, HIGHLIGHT))
                .hoverEvent(HoverEvent.showText(
                        Component.text(desc, SECONDARY).decorate(TextDecoration.BOLD)
                                .append(Component.newline())
                                .append(Component.newline())
                                .append(Component.text(hover, MUTED))
                                .append(Component.newline())
                                .append(Component.newline())
                                .append(Component.text("Click to run", NamedTextColor.GREEN).decorate(TextDecoration.ITALIC))
                ))
                .clickEvent(ClickEvent.suggestCommand(cmd + " "));

        Component descComponent = Component.text(" - ", MUTED)
                .append(Component.text(desc, NamedTextColor.WHITE));

        audience(sender).sendMessage(cmdComponent.append(descComponent));
    }

    private void handleCreate(Player player, String[] args) {
        if (!player.hasPermission("chestshop.create")) {
            sendError(player, "You don't have permission to create shops.");
            return;
        }

        if (args.length < 5) {
            sendError(player, "Usage: /shop create <item> <amount> <buyPrice> <sellPrice>");
            audience(player).sendMessage(Component.text("  Tip: Use 0 to disable buy or sell", MUTED).decorate(TextDecoration.ITALIC));
            return;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !(targetBlock.getState() instanceof Chest)) {
            sendError(player, "You must be looking at a chest.");
            return;
        }

        if (shopManager.isShopChest(targetBlock.getLocation())) {
            sendError(player, "This chest already has a shop.");
            return;
        }

        // Check for double chest conflicts
        if (hasExistingShopOnDoubleChest(targetBlock)) {
            sendError(player, "A shop already exists on this chest.");
            return;
        }

        // Check if player has access to this chest
        if (!canPlayerAccessChest(player, targetBlock)) {
            sendError(player, "You don't have access to this chest.");
            return;
        }

        // Check shop limit per player (unless has bypass permission)
        int maxShops = plugin.getMaxShopsPerPlayer();
        if (maxShops > 0 && !player.hasPermission("chestshop.bypass.limit")) {
            int playerShopCount = shopManager.getPlayerShopCount(player.getUniqueId());
            if (playerShopCount >= maxShops) {
                sendError(player, "You have reached the maximum number of shops (" + maxShops + ")!");
                return;
            }
        }

        Material item;
        try {
            item = Material.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            sendError(player, "Invalid item: " + args[1]);
            return;
        }

        if (!item.isItem()) {
            sendError(player, "That material cannot be traded.");
            return;
        }

        // Check if item is blocked (unless has bypass permission)
        if (plugin.isItemBlocked(item) && !player.hasPermission("chestshop.bypass.blockeditems")) {
            sendError(player, "This item cannot be sold in shops.");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount <= 0 || amount > 64) {
                sendError(player, "Amount must be between 1 and 64.");
                return;
            }
        } catch (NumberFormatException e) {
            sendError(player, "Invalid amount: " + args[2]);
            return;
        }

        double buyPrice;
        try {
            buyPrice = Double.parseDouble(args[3]);
            if (Double.isNaN(buyPrice) || Double.isInfinite(buyPrice)) {
                sendError(player, "Invalid buy price value.");
                return;
            }
            if (buyPrice < 0) {
                sendError(player, "Buy price cannot be negative.");
                return;
            }
            double maxPrice = plugin.getMaxPrice();
            if (buyPrice > maxPrice) {
                sendError(player, "Buy price cannot exceed $" + String.format("%,.0f", maxPrice));
                return;
            }
        } catch (NumberFormatException e) {
            sendError(player, "Invalid buy price: " + args[3]);
            return;
        }

        double sellPrice;
        try {
            sellPrice = Double.parseDouble(args[4]);
            if (Double.isNaN(sellPrice) || Double.isInfinite(sellPrice)) {
                sendError(player, "Invalid sell price value.");
                return;
            }
            if (sellPrice < 0) {
                sendError(player, "Sell price cannot be negative.");
                return;
            }
            double maxPrice = plugin.getMaxPrice();
            if (sellPrice > maxPrice) {
                sendError(player, "Sell price cannot exceed $" + String.format("%,.0f", maxPrice));
                return;
            }
        } catch (NumberFormatException e) {
            sendError(player, "Invalid sell price: " + args[4]);
            return;
        }

        if (buyPrice == 0 && sellPrice == 0) {
            sendError(player, "At least one price must be greater than 0.");
            return;
        }

        // Get the direction the player is facing and find the sign placement
        BlockFace facing = getCardinalDirection(player);
        BlockFace signSide = facing.getOppositeFace(); // Sign goes on opposite side of chest from player
        Block signBlock = targetBlock.getRelative(signSide);

        if (!signBlock.getType().isAir()) {
            sendError(player, "No space on that side of the chest for a sign.");
            return;
        }

        // Check creation cost (unless has bypass permission)
        double creationCost = plugin.getShopCreationCost();
        if (creationCost > 0 && !player.hasPermission("chestshop.bypass.creationcost")) {
            if (!plugin.getEconomy().has(player, creationCost)) {
                sendError(player, "You need $" + String.format("%,.2f", creationCost) + " to create a shop.");
                return;
            }
            plugin.getEconomy().withdrawPlayer(player, creationCost);
            audience(player).sendMessage(Component.text("  " + COIN + " ", SECONDARY)
                    .append(Component.text("Shop creation cost: ", MUTED))
                    .append(Component.text("$" + String.format("%,.2f", creationCost), HIGHLIGHT)));
        }

        // Place wall sign
        signBlock.setType(Material.OAK_WALL_SIGN);
        WallSign wallSignData = (WallSign) signBlock.getBlockData();
        wallSignData.setFacing(signSide); // Sign faces away from chest (toward player)
        signBlock.setBlockData(wallSignData);

        if (signBlock.getState() instanceof Sign sign) {
            // Line 1: ✦ SHOP ✦ (gold)
            sign.setLine(0, "\u00A76\u2726 \u00A7lSHOP \u00A76\u2726");

            // Line 2: Item name (white, bold)
            sign.setLine(1, "\u00A7f\u00A7l" + formatItemName(item));

            // Line 3: ⬆$price ⬇$price (green for buy, aqua for sell)
            String priceLine = "";
            if (buyPrice > 0) priceLine += "\u00A7a\u2B06$" + formatPrice(buyPrice);
            if (buyPrice > 0 && sellPrice > 0) priceLine += " ";
            if (sellPrice > 0) priceLine += "\u00A7b\u2B07$" + formatPrice(sellPrice);
            sign.setLine(2, priceLine);

            // Line 4: Player name (gray)
            sign.setLine(3, "\u00A77" + player.getName());
            sign.update();

            Shop shop = new Shop(
                    player.getUniqueId(),
                    player.getName(),
                    signBlock.getLocation(),
                    targetBlock.getLocation(),
                    item,
                    amount,
                    buyPrice,
                    sellPrice
            );
            shopManager.addShop(shop);

            // Create hologram if enabled
            if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
                plugin.getHologramManager().createHologram(shop);
            }

            sendSuccess(player, "Shop created successfully!");
            audience(player).sendMessage(Component.empty());
            sendShopCard(player, shop, false);
        }
    }

    private BlockFace getCardinalDirection(Player player) {
        float yaw = player.getLocation().getYaw();
        if (yaw < 0) yaw += 360;

        if (yaw >= 315 || yaw < 45) {
            return BlockFace.SOUTH;
        } else if (yaw >= 45 && yaw < 135) {
            return BlockFace.WEST;
        } else if (yaw >= 135 && yaw < 225) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }

    private void handleFind(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chestshop.use")) {
            sendError(sender, "You don't have permission to use this command.");
            return;
        }

        if (args.length < 2) {
            sendError(sender, "Usage: /shop find <item>");
            return;
        }

        Material item;
        try {
            item = Material.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            sendError(sender, "Invalid item: " + args[1]);
            return;
        }

        List<Shop> shops = shopManager.getShopsByItem(item);
        Audience audience = audience(sender);
        if (shops.isEmpty()) {
            audience.sendMessage(Component.empty());
            audience.sendMessage(Component.text("  " + CROSS + " ", ACCENT_ALT)
                    .append(Component.text("No shops found for ", MUTED))
                    .append(Component.text(formatItemName(item), HIGHLIGHT)));
            return;
        }

        audience.sendMessage(Component.empty());
        audience.sendMessage(createHeader("Shops for " + formatItemName(item)));
        audience.sendMessage(Component.text("  Found ", MUTED)
                .append(Component.text(shops.size(), HIGHLIGHT).decorate(TextDecoration.BOLD))
                .append(Component.text(" shop" + (shops.size() != 1 ? "s" : ""), MUTED)));
        audience.sendMessage(Component.empty());

        int count = 0;
        for (Shop shop : shops) {
            if (count++ >= 10) {
                audience.sendMessage(Component.text("  ... and " + (shops.size() - 10) + " more", MUTED).decorate(TextDecoration.ITALIC));
                break;
            }
            sendShopListItem(sender, shop);
        }

        audience.sendMessage(Component.empty());
        audience.sendMessage(createFooter());
    }

    private void sendShopListItem(CommandSender sender, Shop shop) {
        Location loc = shop.getSignLocation();
        String coords = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "Unknown";

        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("  " + BULLET + " ", SECONDARY));
        builder.append(Component.text(shop.getOwnerName(), PRIMARY).decorate(TextDecoration.BOLD));
        builder.append(Component.text(" | ", MUTED));

        if (shop.canBuy()) {
            builder.append(Component.text("BUY ", BUY_COLOR).decorate(TextDecoration.BOLD));
            builder.append(Component.text("$" + formatPrice(shop.getBuyPrice()), BUY_COLOR));
        }
        if (shop.canBuy() && shop.canSell()) {
            builder.append(Component.text(" / ", MUTED));
        }
        if (shop.canSell()) {
            builder.append(Component.text("SELL ", SELL_COLOR).decorate(TextDecoration.BOLD));
            builder.append(Component.text("$" + formatPrice(shop.getSellPrice()), SELL_COLOR));
        }

        Component locationComponent = Component.text(" [" + coords + "]", MUTED)
                .hoverEvent(HoverEvent.showText(
                        Component.text("World: ", MUTED).append(Component.text(worldName, HIGHLIGHT))
                                .append(Component.newline())
                                .append(Component.text("Coordinates: ", MUTED).append(Component.text(coords, HIGHLIGHT)))
                                .append(Component.newline())
                                .append(Component.newline())
                                .append(Component.text("Click to copy coordinates", NamedTextColor.GREEN).decorate(TextDecoration.ITALIC))
                ))
                .clickEvent(ClickEvent.copyToClipboard(coords));

        audience(sender).sendMessage(builder.append(locationComponent).build());
    }

    private void handleInfo(Player player) {
        if (!player.hasPermission("chestshop.use")) {
            sendError(player, "You don't have permission to use this command.");
            return;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null) {
            sendError(player, "You must be looking at a shop sign.");
            return;
        }

        Shop shop = shopManager.getShop(targetBlock.getLocation());
        if (shop == null) {
            sendError(player, "You must be looking at a shop sign.");
            return;
        }

        audience(player).sendMessage(Component.empty());
        sendShopCard(player, shop, true);
    }

    private void sendShopCard(Player player, Shop shop, boolean showRemove) {
        Audience audience = audience(player);
        audience.sendMessage(createHeader("Shop Info"));
        audience.sendMessage(Component.empty());

        // Owner
        audience.sendMessage(Component.text("  Owner       ", MUTED)
                .append(Component.text(shop.getOwnerName(), PRIMARY).decorate(TextDecoration.BOLD)));

        // Item
        audience.sendMessage(Component.text("  Item        ", MUTED)
                .append(Component.text(shop.getAmount() + "x ", HIGHLIGHT))
                .append(Component.text(formatItemName(shop.getItem()), NamedTextColor.WHITE)));

        // Prices
        if (shop.canBuy()) {
            audience.sendMessage(Component.text("  Buy Price   ", MUTED)
                    .append(Component.text("$" + formatPrice(shop.getBuyPrice()), BUY_COLOR).decorate(TextDecoration.BOLD))
                    .append(Component.text(" (right-click to buy)", MUTED).decorate(TextDecoration.ITALIC)));
        }
        if (shop.canSell()) {
            audience.sendMessage(Component.text("  Sell Price  ", MUTED)
                    .append(Component.text("$" + formatPrice(shop.getSellPrice()), SELL_COLOR).decorate(TextDecoration.BOLD))
                    .append(Component.text(" (left-click to sell)", MUTED).decorate(TextDecoration.ITALIC)));
        }

        // Location (only show to staff)
        if (player.hasPermission("chestshop.admin")) {
            Location loc = shop.getChestLocation();
            String coords = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
            audience.sendMessage(Component.text("  Location    ", MUTED)
                    .append(Component.text("(" + coords + ")", MUTED)));
        }

        // Actions (if owner)
        if (showRemove && (shop.getOwnerUUID().equals(player.getUniqueId()) || player.hasPermission("chestshop.admin"))) {
            audience.sendMessage(Component.empty());
            Component removeBtn = Component.text("  [", MUTED)
                    .append(Component.text(CROSS + " Remove Shop", ACCENT_ALT).decorate(TextDecoration.BOLD))
                    .append(Component.text("]", MUTED))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to remove this shop", ACCENT_ALT)))
                    .clickEvent(ClickEvent.runCommand("/shop remove"));
            audience.sendMessage(removeBtn);
        }

        audience.sendMessage(Component.empty());
        audience.sendMessage(createFooter());
    }

    private void handleRemove(Player player) {
        if (!player.hasPermission("chestshop.create")) {
            sendError(player, "You don't have permission to remove shops.");
            return;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null) {
            sendError(player, "You must be looking at a shop sign.");
            return;
        }

        Shop shop = shopManager.getShop(targetBlock.getLocation());
        if (shop == null) {
            sendError(player, "You must be looking at a shop sign.");
            return;
        }

        if (!shop.getOwnerUUID().equals(player.getUniqueId()) && !player.hasPermission("chestshop.admin")) {
            sendError(player, "You can only remove your own shops.");
            return;
        }

        // Remove hologram first, then shop from database, then break sign
        if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
            plugin.getHologramManager().removeHologram(shop);
        }
        Location signLocation = targetBlock.getLocation();
        shopManager.removeShop(signLocation);
        targetBlock.setType(Material.AIR);

        Audience audience = audience(player);
        audience.sendMessage(Component.empty());
        audience.sendMessage(Component.text("  " + CHECK + " ", ACCENT)
                .append(Component.text("Shop removed successfully!", NamedTextColor.WHITE)));
        audience.sendMessage(Component.text("    Item: ", MUTED)
                .append(Component.text(formatItemName(shop.getItem()), HIGHLIGHT)));
        audience.sendMessage(Component.empty());
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("chestshop.admin")) {
            sendError(sender, "You don't have permission to reload shops.");
            return;
        }

        // Remove existing holograms before reload
        if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
            plugin.getHologramManager().removeAllHolograms();
        }

        plugin.reloadConfiguration();
        shopManager.reloadShops();
        int count = shopManager.getAllShops().size();

        // Recreate holograms for loaded shops
        if (plugin.isHologramsEnabled() && plugin.getHologramManager() != null) {
            plugin.getHologramManager().createAllHolograms();
        }

        Audience audience = audience(sender);
        audience.sendMessage(Component.empty());
        audience.sendMessage(Component.text("  " + CHECK + " ", ACCENT)
                .append(Component.text("ChestShop reloaded!", NamedTextColor.WHITE)));
        audience.sendMessage(Component.text("    Config ", MUTED)
                .append(Component.text("reloaded", ACCENT)));
        audience.sendMessage(Component.text("    Loaded ", MUTED)
                .append(Component.text(count, HIGHLIGHT).decorate(TextDecoration.BOLD))
                .append(Component.text(" shop" + (count != 1 ? "s" : "") + " from disk", MUTED)));
        audience.sendMessage(Component.empty());
    }

    // ===== UI Helpers =====

    private Component createHeader(String title) {
        return Component.text("  " + STAR + " ", SECONDARY)
                .append(Component.text(title, PRIMARY).decorate(TextDecoration.BOLD))
                .append(Component.text(" " + STAR, SECONDARY));
    }

    private Component createFooter() {
        return Component.text("  ", MUTED)
                .append(Component.text("---", MUTED))
                .append(Component.text(" ChestShop ", TextColor.color(0x666666)).decorate(TextDecoration.ITALIC))
                .append(Component.text("---", MUTED));
    }

    private void sendError(CommandSender sender, String message) {
        audience(sender).sendMessage(Component.text("  " + CROSS + " ", ACCENT_ALT)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void sendSuccess(CommandSender sender, String message) {
        audience(sender).sendMessage(Component.text("  " + CHECK + " ", ACCENT)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.valueOf((long) price);
        }
        return String.format("%.2f", price);
    }

    // Cache for formatted item names to avoid repeated string operations
    private static final Map<Material, String> itemNameCache = new HashMap<>();

    private String formatItemName(Material material) {
        return itemNameCache.computeIfAbsent(material, m -> {
            String[] words = m.name().toLowerCase().split("_");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (!result.isEmpty()) result.append(" ");
                result.append(word.substring(0, 1).toUpperCase()).append(word.substring(1));
            }
            return result.toString();
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList("help", "create", "find", "info", "remove"));
            if (sender.hasPermission("chestshop.admin")) {
                subCommands.add("reload");
            }
            String input = args[0].toLowerCase();
            completions = subCommands.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("create") || subCommand.equals("find")) {
                String input = args[1].toUpperCase();
                completions = Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(Material::name)
                        .filter(s -> s.startsWith(input))
                        .limit(20)
                        .collect(Collectors.toList());
            }
        } else if (args.length >= 3 && args.length <= 5 && args[0].equalsIgnoreCase("create")) {
            if (args.length == 3) {
                completions.add("<amount>");
            } else if (args.length == 4) {
                completions.add("<buyPrice>");
            } else {
                completions.add("<sellPrice>");
            }
        }

        return completions;
    }

    /**
     * Check if a shop already exists on a double chest
     */
    private boolean hasExistingShopOnDoubleChest(Block chestBlock) {
        if (!(chestBlock.getState() instanceof Chest chest)) {
            return false;
        }

        Inventory inventory = chest.getInventory();
        if (inventory.getHolder() instanceof DoubleChest doubleChest) {
            Chest leftChest = (Chest) doubleChest.getLeftSide();
            Chest rightChest = (Chest) doubleChest.getRightSide();

            if (leftChest != null && shopManager.getShopByChest(leftChest.getLocation()) != null) {
                return true;
            }
            if (rightChest != null && shopManager.getShopByChest(rightChest.getLocation()) != null) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a player has access to a chest (ownership/protection check)
     * Note: This fires a real BlockBreakEvent which some plugins may log.
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

}
