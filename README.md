# ChestShop

A lightweight, fully-featured chest shop plugin for Spigot/Paper 1.21+

![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-green)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## Features

- **Easy Shop Creation** - Place a sign on a chest or use `/shop create`
- **Buy & Sell** - Support for buying, selling, or both
- **Beautiful UI** - Clean, modern chat interface with clickable elements
- **Holograms** - Floating text above shops (requires DecentHolograms)
- **Stock Alerts** - Notifies owners when stock is low (even when offline)
- **Protection** - Shops protected from explosions, pistons, hoppers, and more
- **Fully Configurable** - Customize limits, prices, taxes, messages, and blocked items
- **Optimized** - Efficient performance even with thousands of shops

---

## Requirements

- **Server:** Paper/Spigot 1.21+
- **Java:** 21
- **Required:** [Vault](https://www.spigotmc.org/resources/vault.34315/) + Economy plugin (e.g., [EssentialsX](https://essentialsx.net/))
- **Optional:** [DecentHolograms](https://www.spigotmc.org/resources/decentholograms.96927/) for floating text

---

## Installation

1. Install Vault and an economy plugin
2. Download `ChestShop.jar` and place in your `plugins` folder
3. Restart the server
4. (Optional) Install DecentHolograms for floating shop displays

---

## Creating a Shop

### Method 1: Sign

1. Place a chest
2. Place a sign on the chest
3. Write on the sign:

```
[Shop]
B 10 : S 5
64 Diamond
```

| Line | Content | Example |
|------|---------|---------|
| 1 | `[Shop]` | `[Shop]` |
| 2 | Prices: `B <buy>` and/or `S <sell>` | `B 100` or `S 50` or `B 100 : S 50` |
| 3 | `<amount> <item>` | `64 Diamond` |
| 4 | Leave blank (auto-fills your name) | |

### Method 2: Command

Look at a chest and run:
```
/shop create <item> <amount> <buyPrice> <sellPrice>
```

Example:
```
/shop create diamond 64 100 50
```
Use `0` to disable buy or sell.

---

## Using a Shop

| Action | What it does |
|--------|--------------|
| **Right-click** sign | Buy items from the shop |
| **Left-click** sign | Sell items to the shop |

---

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/shop help` | Show all commands | `chestshop.use` |
| `/shop create <item> <amt> <buy> <sell>` | Create a shop | `chestshop.create` |
| `/shop find <item>` | Find shops for an item | `chestshop.use` |
| `/shop info` | View shop details (look at sign) | `chestshop.use` |
| `/shop remove` | Remove your shop (look at sign) | `chestshop.create` |
| `/shop reload` | Reload config and shops | `chestshop.admin` |

**Aliases:** `/chestshop`, `/cs`

---

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `chestshop.use` | Use shops and basic commands | Everyone |
| `chestshop.create` | Create and remove shops | Everyone |
| `chestshop.admin` | Admin commands, manage all shops | OP |
| `chestshop.bypass.limit` | Bypass max shops limit | OP |
| `chestshop.bypass.creationcost` | Free shop creation | OP |
| `chestshop.bypass.blockeditems` | Use blocked items | OP |

---

## Configuration

```yaml
# Shop Limits
max-shops-per-player: 50    # 0 = unlimited
max-price: 1000000000       # Maximum price allowed

# Economy
shop-creation-cost: 0       # Cost to create a shop
transaction-tax-percent: 0  # Tax on sales (0-100)

# Alerts (notifies shop owners)
alerts:
  enabled: true
  low-stock-threshold: 10
  low-money-threshold: 100

# Holograms (requires DecentHolograms)
holograms:
  enabled: true

# Blocked items (cannot be sold)
blocked-items:
  - BEDROCK
  - BARRIER
  - COMMAND_BLOCK
  # ... see config.yml for full list
```

---

## File Structure

```
plugins/ChestShop/
├── config.yml     # Plugin configuration
├── shops.yml      # Shop data (auto-generated)
└── alerts.yml     # Pending offline alerts
```

---

## Building from Source

```bash
mvn clean package
```

The compiled JAR will be in `target/`

---

## Support

For issues and feature requests, please open an issue on GitHub.

---

## License

MIT License - Feel free to use and modify.
