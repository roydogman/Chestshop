package com.chestshop.models;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.UUID;

public class Shop {

    private final UUID ownerUUID;
    private final String ownerName;
    private final Location signLocation;
    private final Location chestLocation;
    private final Material item;
    private final int amount;
    private final double buyPrice;  // Price for players to buy (0 = not for sale)
    private final double sellPrice; // Price for players to sell (0 = not buying)

    public Shop(UUID ownerUUID, String ownerName, Location signLocation, Location chestLocation,
                Material item, int amount, double buyPrice, double sellPrice) {
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.signLocation = signLocation;
        this.chestLocation = chestLocation;
        this.item = item;
        this.amount = amount;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public Location getSignLocation() {
        // Return a clone to prevent external modification
        return signLocation.clone();
    }

    public Location getChestLocation() {
        // Return a clone to prevent external modification
        return chestLocation.clone();
    }

    public Material getItem() {
        return item;
    }

    public int getAmount() {
        return amount;
    }

    public double getBuyPrice() {
        return buyPrice;
    }

    public double getSellPrice() {
        return sellPrice;
    }

    public boolean canBuy() {
        return buyPrice > 0;
    }

    public boolean canSell() {
        return sellPrice > 0;
    }
}
