package org.neimeyer.multiDiscJukebox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public final class MultiDiscJukebox extends JavaPlugin {
    private final Map<Location, ItemStack[]> inventories = new HashMap<>();
    private final Map<Location, DiscPlayer> discPlayers = new HashMap<>();

    private NamespacedKey cyclerKey;
    private NamespacedKey multiboxKey;

    private JukeboxListener jukeboxListener;

    private boolean debugEnabled = false;
    private Map<String, Boolean> logLevels = new HashMap<>();
    private Map<String, Boolean> logCategories = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLoggingConfig();

        this.cyclerKey = new NamespacedKey(this, "multibox");
        this.multiboxKey = new NamespacedKey(this, "multibox_craft");

        // Load disc durations from config
        DiscPlayer.loadDiscDurations(this);

        // Register recipes, commands and listeners
        registerRecipes();
        registerCommands();

        // Store the listener instance
        this.jukeboxListener = new JukeboxListener(this);
        getServer().getPluginManager().registerEvents(jukeboxListener, this);

        // Schedule the cleanup task to run every minute
        getServer().getScheduler().runTaskTimer(this, () -> jukeboxListener.cleanupHandledEvents(), 20 * 60, 20 * 60);

        // Load saved inventories from data.yml
        loadData();

        getLogger().info("MultiBox enabled! Use /multibox give");
    }

    private void loadData() {
        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            getLogger().info("No data.yml found, no MultiBoxes to load");
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        int validEntries = 0;
        int invalidEntries = 0;

        getLogger().info("Loading MultiBox data from data.yml...");

        for (String key : cfg.getKeys(false)) {
            try {
                Location loc = locationFromString(key);

                // Skip if the world doesn't exist
                if (loc.getWorld() == null) {
                    getLogger().warning("Skipping MultiBox at " + key + ": World doesn't exist");
                    invalidEntries++;
                    continue;
                }

                // Check if the block at this location is a player head without loading chunks
                // We'll only verify if the chunk is generated
                if (!loc.getWorld().isChunkGenerated(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                    getLogger().warning("Skipping MultiBox at " + key + ": Chunk not generated");
                    invalidEntries++;
                    continue;
                }

                @SuppressWarnings("unchecked") List<ItemStack> itemList = (List<ItemStack>) cfg.get(key);
                if (itemList == null) {
                    getLogger().warning("Skipping MultiBox at " + key + ": No items found");
                    invalidEntries++;
                    continue;
                }

                ItemStack[] contents = itemList.toArray(new ItemStack[0]);
                inventories.put(loc, contents);

                // Count valid discs for logging
                int validDiscs = 0;
                for (ItemStack item : contents) {
                    if (item != null && item.getType().toString().toLowerCase().contains("music_disc")) {
                        validDiscs++;
                    }
                }

                getLogger().info("Loaded MultiBox at " + key + " with " + validDiscs + " music discs");

                // Start the disc player for this location
                DiscPlayer dp = new DiscPlayer(this, loc, contents);
                discPlayers.put(loc, dp);
                dp.start();
                validEntries++;
            } catch (Exception e) {
                getLogger().warning("Failed to load MultiBox at " + key + ": " + e.getMessage());
                invalidEntries++;
            }
        }

        getLogger().info("Loaded " + validEntries + " MultiBoxes successfully");
        if (invalidEntries > 0) {
            getLogger().warning("Found " + invalidEntries + " invalid entries in data.yml");
        }
    }

    private void loadLoggingConfig() {
        // Set defaults in case config values are missing
        debugEnabled = getConfig().getBoolean("logging.debug", false);

        // Load log levels
        ConfigurationSection levelSection = getConfig().getConfigurationSection("logging.levels");
        if (levelSection != null) {
            for (String key : levelSection.getKeys(false)) {
                logLevels.put(key, levelSection.getBoolean(key, true));
            }
        } else {
            // Set defaults if section doesn't exist
            logLevels.put("info", true);
            logLevels.put("warning", true);
            logLevels.put("error", true);
        }

        // Load log categories
        ConfigurationSection categorySection = getConfig().getConfigurationSection("logging.categories");
        if (categorySection != null) {
            for (String key : categorySection.getKeys(false)) {
                logCategories.put(key, categorySection.getBoolean(key, true));
            }
        } else {
            // Set defaults if section doesn't exist
            logCategories.put("disc_player", true);
            logCategories.put("commands", true);
            logCategories.put("block_events", true);
            logCategories.put("disc_durations", true);
        }
    }

    private void registerCommands() {
        getServer().getCommandMap().register("multibox", new Command("multibox") {
            {
                setDescription("Manage MultiBox");
                setUsage("/multibox give [player] [amount] | status | reload | discs | disc <add|set> <name> <seconds>");
            }

            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (args.length >= 1) {
                    // Commands that don't require a player
                    if (args[0].equalsIgnoreCase("reload")) {
                        if (!sender.hasPermission("multibox.dj")) {
                            sender.sendMessage(Component.text("You don't have permission to reload the plugin").color(NamedTextColor.RED));
                            return true;
                        }

                        // Reload disc durations
                        DiscPlayer.loadDiscDurations(MultiDiscJukebox.this);
                        sender.sendMessage(Component.text("MultiBox disc durations reloaded").color(NamedTextColor.GREEN));
                        return true;
                    } else if (args[0].equalsIgnoreCase("discs")) {
                        if (!sender.hasPermission("multibox.dj")) {
                            sender.sendMessage(Component.text("You don't have permission to view disc durations").color(NamedTextColor.RED));
                            return true;
                        }

                        // Show all disc durations
                        List<String> durations = DiscPlayer.getDiscDurationsList(MultiDiscJukebox.this);
                        for (String line : durations) {
                            sender.sendMessage(Component.text(line));
                        }
                        return true;
                    } else if (args[0].equalsIgnoreCase("disc")) {
                        if (!sender.hasPermission("multibox.dj")) {
                            sender.sendMessage(Component.text("You don't have permission to manage disc durations").color(NamedTextColor.RED));
                            return true;
                        }

                        if (args.length < 4) {
                            sender.sendMessage(Component.text("Usage: /multibox disc <add|set> <name> <seconds>").color(NamedTextColor.RED));
                            return true;
                        }

                        String operation = args[1].toLowerCase();
                        String discName = args[2].toUpperCase();
                        int seconds;

                        // Parse seconds
                        try {
                            seconds = Integer.parseInt(args[3]);
                            if (seconds <= 0) {
                                sender.sendMessage(Component.text("Duration must be greater than 0 seconds").color(NamedTextColor.RED));
                                return true;
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(Component.text("Invalid duration: " + args[3]).color(NamedTextColor.RED));
                            return true;
                        }

                        // Add prefix if needed
                        if (!discName.startsWith("MUSIC_DISC_") && operation.equals("add")) {
                            discName = "MUSIC_DISC_" + discName;
                        }

                        // Verify if it's a valid material for "set" operation
                        if (operation.equals("set")) {
                            try {
                                Material.valueOf(discName);
                            } catch (IllegalArgumentException e) {
                                sender.sendMessage(Component.text("Unknown disc material: " + discName).color(NamedTextColor.RED));
                                return true;
                            }
                        }

                        // Update the duration
                        boolean success = DiscPlayer.updateDiscDuration(MultiDiscJukebox.this, discName, seconds);

                        if (success) {
                            // Auto reload durations
                            DiscPlayer.loadDiscDurations(MultiDiscJukebox.this);

                            int minutes = seconds / 60;
                            int remainingSeconds = seconds % 60;
                            String formattedTime = String.format("%d:%02d", minutes, remainingSeconds);

                            sender.sendMessage(Component.text("Updated " + discName + " duration to " + formattedTime).color(NamedTextColor.GREEN));
                        } else {
                            sender.sendMessage(Component.text("Failed to update disc duration").color(NamedTextColor.RED));
                        }
                        return true;
                    } else if (args[0].equalsIgnoreCase("give")) {
                        if (!sender.hasPermission("multibox.admin")) {
                            sender.sendMessage(Component.text("You don't have permission to give MultiBoxes").color(NamedTextColor.RED));
                            return true;
                        }

                        // Enhanced give command with optional player target
                        Player targetPlayer = null;
                        int amount = 1;
                        int argIndex = 1;

                        // Check if the next argument is a player name
                        if (args.length > argIndex) {
                            targetPlayer = Bukkit.getPlayer(args[argIndex]);
                            if (targetPlayer != null) {
                                // Found a valid player, move to the next arg
                                argIndex++;
                            }
                        }

                        // Parse amount if provided
                        if (args.length > argIndex) {
                            try {
                                amount = Integer.parseInt(args[argIndex]);
                                if (amount < 1) amount = 1;
                                if (amount > 64) amount = 64;
                            } catch (NumberFormatException e) {
                                sender.sendMessage(Component.text("Invalid amount: " + args[argIndex] + ". Using 1 instead.").color(NamedTextColor.RED));
                                amount = 1;
                            }
                        }

                        // If no target player was specified or found, use the sender if it's a player
                        if (targetPlayer == null) {
                            if (sender instanceof Player) {
                                targetPlayer = (Player) sender;
                            } else {
                                sender.sendMessage(Component.text("Please specify a player name when using this command from console").color(NamedTextColor.RED));
                                return true;
                            }
                        }

                        // Give the multibox to the target player
                        giveMultiBox(targetPlayer, amount, sender);
                        return true;
                    }

                    // Command that always requires a player sender
                    if (args[0].equalsIgnoreCase("status")) {
                        if (!(sender instanceof Player p)) {
                            sender.sendMessage("This command can only be used by a player");
                            return true;
                        }

                        // Find the nearest MultiBox
                        Location playerLoc = p.getLocation();
                        Location closest = null;
                        double minDist = Double.MAX_VALUE;

                        for (Location loc : discPlayers.keySet()) {
                            if (loc.getWorld().equals(playerLoc.getWorld())) {
                                double dist = loc.distance(playerLoc);
                                if (dist < minDist && dist <= 10) { // Only consider within 10 blocks
                                    minDist = dist;
                                    closest = loc;
                                }
                            }
                        }

                        if (closest != null) {
                            DiscPlayer dp = discPlayers.get(closest);
                            p.sendMessage(Component.text("MultiBox at " + closest.getBlockX() + "," + closest.getBlockY() + "," + closest.getBlockZ() + ": " + dp.getPlaybackInfo()).color(NamedTextColor.GREEN));

                            // Also log to console
                            getLogger().info("MultiBox status: " + dp.getPlaybackInfo());
                            return true;
                        } else {
                            p.sendMessage(Component.text("No MultiBox found within 10 blocks.").color(NamedTextColor.RED));
                            return true;
                        }
                    }
                }

                sender.sendMessage(Component.text("Usage: /multibox give [player] [amount] | status | reload | discs | disc <add|set> <name> <seconds>").color(NamedTextColor.RED));
                return true;
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                if (args.length == 1) {
                    List<String> options = new ArrayList<>();

                    // Always show status to all players
                    options.add("status");

                    // Only show give to admin
                    if (sender.hasPermission("multibox.admin")) {
                        options.add("give");
                    }

                    // Only show disc commands and reload to DJs
                    if (sender.hasPermission("multibox.dj")) {
                        options.add("discs");
                        options.add("disc");
                        options.add("reload");
                    }

                    return options;
                } else if (args.length == 2) {
                    if (args[0].equalsIgnoreCase("give") && sender.hasPermission("multibox.admin")) {
                        // Return list of online players plus some common amounts
                        List<String> suggestions = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            suggestions.add(p.getName());
                        }
                        suggestions.addAll(List.of("1", "16", "32", "64"));
                        return suggestions;
                    } else if (args[0].equalsIgnoreCase("disc") && sender.hasPermission("multibox.dj")) {
                        return List.of("add", "set");
                    }
                } else if (args.length == 3) {
                    if (args[0].equalsIgnoreCase("give") && sender.hasPermission("multibox.admin")) {
                        try {
                            // If arg[1] is a player name, suggest amounts
                            if (Bukkit.getPlayer(args[1]) != null) {
                                return List.of("1", "16", "32", "64");
                            }
                        } catch (Exception e) {
                            // If not a player, do nothing special
                        }
                    } else if (args[0].equalsIgnoreCase("disc") && args[1].equalsIgnoreCase("set") && sender.hasPermission("multibox.dj")) {
                        // Return a list of all music disc materials
                        List<String> discs = new ArrayList<>();
                        for (Material mat : Material.values()) {
                            if (mat.name().contains("MUSIC_DISC")) {
                                discs.add(mat.name());
                            }
                        }
                        return discs;
                    } else if (args[0].equalsIgnoreCase("disc") && args[1].equalsIgnoreCase("add") && sender.hasPermission("multibox.dj")) {
                        // Suggest some common names for custom discs
                        return List.of("CUSTOM", "MOD", "MODDED");
                    }
                } else if (args.length == 4) {
                    if (args[0].equalsIgnoreCase("disc") && sender.hasPermission("multibox.dj")) {
                        // Suggest some common durations in seconds
                        return List.of("120", "180", "240", "300");
                    }
                }
                return List.of();
            }
        });
    }


    @Override
    public void onDisable() {
        getLogger().info("Stopping all DiscPlayers...");

        // Stop all music players
        for (Map.Entry<Location, DiscPlayer> entry : discPlayers.entrySet()) {
            try {
                entry.getValue().stop();
                getLogger().info("Stopped DiscPlayer at " + locationToString(entry.getKey()));
            } catch (Exception e) {
                getLogger().warning("Error stopping DiscPlayer: " + e.getMessage());
            }
        }
        discPlayers.clear();

        // Save all inventory data
        saveData();

        getLogger().info("MultiBox disabled");
    }

    private void saveData() {
        if (inventories.isEmpty()) {
            getLogger().info("No inventories to save");
            return;
        }

        File dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration cfg = new YamlConfiguration();
        int savedEntries = 0;

        getLogger().info("Saving " + inventories.size() + " MultiBox inventories...");

        for (var entry : inventories.entrySet()) {
            try {
                cfg.set(locationToString(entry.getKey()), entry.getValue());
                savedEntries++;
            } catch (Exception e) {
                getLogger().severe("Failed to save jukebox at " + locationToString(entry.getKey()) + ": " + e.getMessage());
            }
        }

        try {
            cfg.save(dataFile);
            getLogger().info("Successfully saved " + savedEntries + " MultiBox inventories");
        } catch (IOException ex) {
            getLogger().severe("Failed to save data.yml: " + ex.getMessage());
        }
    }


    /**
     * Gives a MultiBox item to the specified player
     *
     * @param player The player to receive the item
     * @param amount The amount of items to give
     * @param sender The CommandSender who initiated the command (for messages)
     */
    public void giveMultiBox(Player player, int amount, CommandSender sender) {
        ItemStack item = getMultiBoxItem();
        item.setAmount(amount);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);

        if (!leftover.isEmpty()) {
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }

            // Message to the recipient
            player.sendMessage(Component.text("Some items were dropped at your feet because your inventory is full").color(NamedTextColor.GOLD));

            // Message to the sender if different from recipient
            if (sender != player) {
                sender.sendMessage(Component.text("Some items were dropped at " + player.getName() + "'s feet because their inventory is full").color(NamedTextColor.GOLD));
            }
        } else {
            // Message to the recipient
            player.sendMessage(Component.text("You received " + amount + " MultiBox" + (amount > 1 ? "es" : "!")).color(NamedTextColor.GOLD));

            // Message to the sender if different from recipient
            if (sender != player) {
                sender.sendMessage(Component.text("Gave " + amount + " MultiBox" + (amount > 1 ? "es" : "") + " to " + player.getName()).color(NamedTextColor.GREEN));
            }
        }
    }

    // Legacy method to maintain compatibility with existing code
    public void giveMultiBox(Player player, int amount) {
        giveMultiBox(player, amount, player);
    }

    public ItemStack getMultiBoxItem() {
        // Create the head item
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);

        if (head.getItemMeta() instanceof SkullMeta skullMeta) {
            // Set basic metadata
            skullMeta.getPersistentDataContainer().set(cyclerKey, PersistentDataType.BYTE, (byte) 1);
            skullMeta.displayName(Component.text("MultiBox", NamedTextColor.GOLD));
            skullMeta.lore(List.of(Component.text("This jukebox can play multiple discs", NamedTextColor.GRAY)));

            // Set the custom texture
            try {
                // Create a PlayerProfile with this UUID
                UUID id = new UUID(-1850217977L, 998918894L);
                com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(id, null);

                // Set the texture property
                String textureValue = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGY1NjU4ZmM2N2FhNTNjNmIxMzMwZDI3OTBiMDgzMDE2Y2NhNGU3NzkxYTI0Y2ExNzg3ZmQ3MzA3YjUyZGRhMyJ9fX0=";
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", textureValue));

                // Apply the profile to the skull
                skullMeta.setPlayerProfile(profile);
            } catch (Exception e) {
                getLogger().warning("Failed to set custom skull texture: " + e.getMessage());
            }

            head.setItemMeta(skullMeta);
        }

        return head;
    }

    private void registerRecipes() {
        /* ---------------- remove old copies --------------- */
        Bukkit.removeRecipe(this.multiboxKey);

        /* ---------------- shaped "8 jukebox → multibox" --- */
        ShapedRecipe multibox = new ShapedRecipe(this.multiboxKey, getMultiBoxItem());
        multibox.shape("XXX", "X X", "XXX");
        multibox.setIngredient('X', Material.JUKEBOX);
        Bukkit.addRecipe(multibox);
    }

    private String locationToString(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location locationFromString(String locString) {
        String[] parts = locString.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid location string format: " + locString);
        }

        return new Location(Bukkit.getWorld(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    public Map<Location, ItemStack[]> getInventories() {
        return inventories;
    }

    public NamespacedKey getCyclerKey() {
        return cyclerKey;
    }

    public NamespacedKey getMultiboxKey() {
        return multiboxKey;
    }

    public Map<Location, DiscPlayer> getDiscPlayers() {
        return discPlayers;
    }

    // Add logging utility methods
    public void logDebug(String category, String message) {
        if (debugEnabled && shouldLog(category)) {
            getLogger().info("[DEBUG] [" + category + "] " + message);
        }
    }

    public void logInfo(String category, String message) {
        if (logLevels.getOrDefault("info", true) && shouldLog(category)) {
            getLogger().info("[" + category + "] " + message);
        }
    }

    public void logWarning(String category, String message) {
        if (logLevels.getOrDefault("warning", true) && shouldLog(category)) {
            getLogger().warning("[" + category + "] " + message);
        }
    }

    public void logError(String category, String message, Throwable error) {
        if (logLevels.getOrDefault("error", true) && shouldLog(category)) {
            if (error != null) {
                getLogger().log(Level.SEVERE, "[" + category + "] " + message, error);
            } else {
                getLogger().severe("[" + category + "] " + message);
            }
        }
    }

    private boolean shouldLog(String category) {
        return logCategories.getOrDefault(category, true);
    }

    // Add a method to reload logging configuration
    public void reloadLoggingConfig() {
        reloadConfig();
        loadLoggingConfig();
        logInfo("commands", "Logging configuration reloaded");
    }


}