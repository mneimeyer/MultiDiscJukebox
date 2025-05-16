package org.neimeyer.multiDiscJukebox;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiscPlayer {
    // Constants
    private static final double JUKEBOX_RANGE = 64.0; // Standard Minecraft jukebox range
    // Map of disc types to their durations in ticks (1 second = 20 ticks)
    private static Map<Material, Integer> DISC_DURATIONS = new HashMap<>();
    // Core properties
    private final MultiDiscJukebox plugin;
    private final Location location;
    private ItemStack[] discs;
    // Playback state
    private int currentIndex = -1; // Start with -1 to ensure the first disc is 0
    private BukkitTask particleTask; // Task for spawning particles
    private BukkitTask discFinishTask; // Task to track when current disc should finish
    private boolean isPaused = false;
    private boolean isPlaying = false;
    private Material currentDiscType = null;
    private long playStartTime = 0; // When the current disc started playing
    private int playCountdown = 0; // Countdown timer for current disc (in ticks)
    private boolean acceleratedEnd = false; // Flag to prevent double disc advancement

    /**
     * Creates a new disc player for the given location with the specified discs
     *
     * @param plugin   The MultiDiscJukebox plugin instance
     * @param location The location of this jukebox
     * @param discs    The array of discs in the jukebox
     */
    public DiscPlayer(MultiDiscJukebox plugin, Location location, ItemStack[] discs) {
        this.plugin = plugin;
        this.location = location;
        this.discs = discs;

        // Log initialization
        plugin.logInfo("disc_player", "Created DiscPlayer at " + formatLocation(location));
    }

    // Load disc durations from config
    public static void loadDiscDurations(MultiDiscJukebox plugin) {
        // Clear existing durations
        DISC_DURATIONS.clear();

        // First set the default durations
        Map<String, Integer> defaultDurations = new HashMap<>();
        defaultDurations.put("MUSIC_DISC_13", 20 * 178);      // 2:58
        defaultDurations.put("MUSIC_DISC_CAT", 20 * 185);     // 3:05
        defaultDurations.put("MUSIC_DISC_BLOCKS", 20 * 345);  // 5:45
        defaultDurations.put("MUSIC_DISC_CHIRP", 20 * 185);   // 3:05
        defaultDurations.put("MUSIC_DISC_FAR", 20 * 174);     // 2:54
        defaultDurations.put("MUSIC_DISC_MALL", 20 * 197);    // 3:17
        defaultDurations.put("MUSIC_DISC_MELLOHI", 20 * 96);  // 1:36
        defaultDurations.put("MUSIC_DISC_STAL", 20 * 150);    // 2:30
        defaultDurations.put("MUSIC_DISC_STRAD", 20 * 188);   // 3:08
        defaultDurations.put("MUSIC_DISC_WARD", 20 * 251);    // 4:11
        defaultDurations.put("MUSIC_DISC_11", 20 * 71);       // 1:11
        defaultDurations.put("MUSIC_DISC_WAIT", 20 * 238);    // 3:58
        defaultDurations.put("MUSIC_DISC_OTHERSIDE", 20 * 195); // 3:15
        defaultDurations.put("MUSIC_DISC_5", 20 * 178);       // 2:58
        defaultDurations.put("MUSIC_DISC_PIGSTEP", 20 * 148); // 2:28
        defaultDurations.put("MUSIC_DISC_RELIC", 20 * 215);   // 3:35

        // Check for disc_durations.yml
        File durationFile = new File(plugin.getDataFolder(), "disc_durations.yml");
        boolean saveNewConfig = false;

        // Create file if it doesn't exist
        if (!durationFile.exists()) {
            plugin.logInfo("disc_durations", "Creating new disc_durations.yml with default values");
            saveNewConfig = true;
        }

        // Load or create the configuration
        YamlConfiguration config = YamlConfiguration.loadConfiguration(durationFile);

        // Add any missing default durations
        for (Map.Entry<String, Integer> entry : defaultDurations.entrySet()) {
            String discName = entry.getKey();

            // If disc duration not in config, add default
            if (!config.contains(discName)) {
                config.set(discName, entry.getValue() / 20); // Store in seconds for easier editing
                saveNewConfig = true;
            }

            // Try to get the Material type
            try {
                Material discType = Material.valueOf(discName);

                // Duration in config is in seconds, convert to ticks (1 second = 20 ticks)
                int durationInSeconds = config.getInt(discName);
                DISC_DURATIONS.put(discType, durationInSeconds * 20);

                plugin.logInfo("disc_durations", "Loaded duration for " + discName + ": " + durationInSeconds + " seconds");
            } catch (IllegalArgumentException e) {
                plugin.logWarning("disc_player", "Could not find Material type for " + discName);
            }
        }

        // Save config if we added any defaults
        if (saveNewConfig) {
            try {
                config.save(durationFile);
                plugin.logInfo("disc_durations", "Saved disc_durations.yml with default values");
            } catch (IOException e) {
                plugin.logError("disc_durations", "Failed to save disc_durations.yml", e);
            }
        }

        // Add note in the config
        if (!config.contains("_note")) {
            config.set("_note", "Duration values are in seconds. Edit these to match custom resource packs or future disc lengths.");
            try {
                config.save(durationFile);
            } catch (IOException e) {
                plugin.logWarning("disc_durations", "Could not save note to disc_durations.yml");
            }
        }
    }

    /**
     * Gets the current durations config file
     *
     * @param plugin The plugin instance
     * @return The YamlConfiguration for disc durations
     */
    private static YamlConfiguration getDurationsConfig(MultiDiscJukebox plugin) {
        File durationFile = new File(plugin.getDataFolder(), "disc_durations.yml");
        return YamlConfiguration.loadConfiguration(durationFile);
    }

    /**
     * Updates or adds a disc duration to the configuration
     *
     * @param plugin          The plugin instance
     * @param discName        The name of the disc (e.g., "MUSIC_DISC_CAT")
     * @param durationSeconds The duration in seconds
     * @return True if successful, false otherwise
     */
    public static boolean updateDiscDuration(MultiDiscJukebox plugin, String discName, int durationSeconds) {
        // Validate input
        if (discName == null || discName.isEmpty() || durationSeconds <= 0) {
            return false;
        }

        // Make sure disc name is uppercase for consistency
        discName = discName.toUpperCase();

        // Get the config file
        File durationFile = new File(plugin.getDataFolder(), "disc_durations.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(durationFile);

        // Update the duration
        config.set(discName, durationSeconds);

        // Save the config
        try {
            config.save(durationFile);
            plugin.logInfo("disc_durations", "Updated duration for " + discName + " to " + durationSeconds + " seconds");

            // Update the runtime map as well
            try {
                Material discType = Material.valueOf(discName);
                DISC_DURATIONS.put(discType, durationSeconds * 20); // Convert to ticks
            } catch (IllegalArgumentException e) {
                plugin.logWarning("disc_durations", "Added disc " + discName + " to config, but it's not a valid Material type");
            }

            return true;
        } catch (IOException e) {
            plugin.logError("disc_durations", "Failed to save disc_durations.yml", e);
            return false;
        }
    }

    /**
     * Get a formatted list of all disc durations
     *
     * @param plugin The plugin instance
     * @return A list of strings with disc durations
     */
    public static List<String> getDiscDurationsList(MultiDiscJukebox plugin) {
        YamlConfiguration config = getDurationsConfig(plugin);
        List<String> result = new ArrayList<>();

        result.add("§6Disc Durations:");

        // Get all keys except notes
        for (String key : config.getKeys(false)) {
            if (key.startsWith("_")) continue; // Skip notes/metadata

            int seconds = config.getInt(key);
            int minutes = seconds / 60;
            int remainingSeconds = seconds % 60;

            // Format as mm:ss
            String formattedTime = String.format("%d:%02d", minutes, remainingSeconds);

            // Check if it's a known Material
            boolean isValid = false;
            try {
                Material.valueOf(key);
                isValid = true;
            } catch (IllegalArgumentException e) {
                // Not a valid Material
            }

            // Add to result list (color code: green for valid, yellow for custom)
            result.add((isValid ? "§a" : "§e") + key + "§7: §f" + formattedTime + (isValid ? "" : " §e(custom)"));
        }

        return result;
    }

    /**
     * Automatically add an unknown disc to the durations config
     *
     * @param plugin                 The plugin instance
     * @param discType               The Material type of the disc
     * @param defaultDurationSeconds The default duration in seconds
     * @return True if added successfully
     */
    public static boolean addUnknownDisc(MultiDiscJukebox plugin, Material discType, int defaultDurationSeconds) {
        if (discType == null || !discType.name().contains("MUSIC_DISC")) {
            return false;
        }

        // Check if disc already exists in the config
        if (DISC_DURATIONS.containsKey(discType)) {
            return false; // Already known
        }

        // Add to config and reload
        return updateDiscDuration(plugin, discType.name(), defaultDurationSeconds);
    }

    private String formatLocation(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /**
     * Starts the disc player and begins playback if discs are available
     */
    public void start() {
        stop(); // Stop any existing playback

        plugin.logInfo("disc_player", "Starting DiscPlayer at " + formatLocation(location));

        // Reset state
        this.currentIndex = -1;
        this.isPaused = false;
        this.isPlaying = false;
        this.currentDiscType = null;
        this.playStartTime = 0;
        this.playCountdown = 0;
        this.acceleratedEnd = false;

        // Check if we have discs to play
        if (hasValidDiscs()) {
            plugin.logInfo("disc_player", "Valid discs found, playing immediately");
            playNextDisc();
        } else {
            plugin.logInfo("disc_player", "No valid discs found at " + formatLocation(location));
        }

        // Start the particle effect task
        startParticleEffects();
    }

    /* ----- Disc Handling Methods ----- */

    /**
     * Starts showing particles above the jukebox when playing
     */
    private void startParticleEffects() {
        if (particleTask != null) {
            particleTask.cancel();
        }

        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (isPlaying && !isPaused) {
                // Spawn note particles above the jukebox
                Location particleLoc = location.clone().add(0.5, 1.2, 0.5);
                location.getWorld().spawnParticle(Particle.NOTE, particleLoc, 1,  // Count
                        0.3, 0.3, 0.3,  // Offset
                        1   // Extra (determines the note color)
                );

                // Log remaining time every 20 seconds (400 ticks)
                if (playCountdown > 0 && playCountdown % 400 == 0) {
                    plugin.logInfo("disc_durations", "Disc " + currentDiscType + " has " + (playCountdown / 20) + " seconds remaining until next disc");
                }
            }
        }, 20L, 20L); // Every second
    }

    /**
     * Stops playback and cancels the playback task
     */
    public void stop() {
        plugin.logInfo("disc_player", "Stopping DiscPlayer at " + formatLocation(location));

        // Make sure we cancel any ongoing tasks
        if (this.particleTask != null) {
            this.particleTask.cancel();
            this.particleTask = null;
        }

        if (this.discFinishTask != null) {
            this.discFinishTask.cancel();
            this.discFinishTask = null;
        }

        // Stop any current music
        stopRecordSound();

        // Update state
        this.isPlaying = false;
        this.isPaused = true;
        this.currentDiscType = null;
        this.playStartTime = 0;
        this.playCountdown = 0;
        this.acceleratedEnd = false;
    }

    /**
     * Sets the paused state of the disc player
     *
     * @param paused True to pause playback, false to resume
     */
    public void setPaused(boolean paused) {
        if (this.isPaused == paused) return; // No change

        this.isPaused = paused;
        plugin.logInfo("disc_player", "DiscPlayer at " + formatLocation(location) + " " + (paused ? "paused" : "resumed"));

        if (paused) {
            stopRecordSound();
            // Cancel the disc finish task when paused
            if (this.discFinishTask != null) {
                this.discFinishTask.cancel();
                this.discFinishTask = null;
            }
        } else if (hasValidDiscs() && isPlaying) {
            // Restart playing if we have discs and just unpaused
            playNextDisc();
        }
    }

    /* ----- Sound Playback Methods ----- */

    /**
     * Updates the discs in this jukebox and manages playback state accordingly
     *
     * @param newDiscs The new array of discs
     */
    public void updateDiscs(ItemStack[] newDiscs) {
        boolean hadValidDiscs = hasValidDiscs();
        boolean willHaveValidDiscs = hasValidDiscs(newDiscs);

        plugin.logInfo("disc_player", "Updating discs at " + formatLocation(location) + ", had valid discs: " + hadValidDiscs + ", will have valid discs: " + willHaveValidDiscs);

        this.discs = newDiscs;

        // Reset index if the current index is out of bounds
        if (this.discs == null || currentIndex >= this.discs.length) {
            currentIndex = -1;
        }

        // Stop all music if we no longer have valid discs
        if (hadValidDiscs && !willHaveValidDiscs) {
            plugin.logInfo("disc_player", "No more valid discs, stopping playback");
            stopRecordSound();
            isPlaying = false;
            currentDiscType = null;
            playStartTime = 0;
            playCountdown = 0;
            acceleratedEnd = false;

            // Cancel disc finish task
            if (this.discFinishTask != null) {
                this.discFinishTask.cancel();
                this.discFinishTask = null;
            }
        }
        // Start playback if we didn't have discs before but do now
        else if (!hadValidDiscs && willHaveValidDiscs && !isPaused) {
            plugin.logInfo("disc_player", "Now have valid discs, starting playback");
            isPlaying = true;
            playNextDisc();
        }
        // If we're currently playing and still have discs, we may need to update playback
        else if (isPlaying && willHaveValidDiscs) {
            // We'll continue playing with the current disc
            plugin.logInfo("disc_player", "Discs updated, continuing with current playback");
        }
    }

    /**
     * Checks if the current set of discs contains at least one valid music disc
     *
     * @return True if there's at least one valid music disc
     */
    private boolean hasValidDiscs() {
        return hasValidDiscs(this.discs);
    }

    /**
     * Checks if the given array of items contains at least one valid music disc
     *
     * @param items The array of items to check
     * @return True if there's at least one valid music disc
     */
    private boolean hasValidDiscs(ItemStack[] items) {
        if (items == null) return false;

        boolean result = Arrays.stream(items).anyMatch(this::isValidMusicDisc);

        if (result) {
            int count = 0;
            for (ItemStack item : items) {
                if (isValidMusicDisc(item)) count++;
            }
            plugin.logInfo("disc_player", "Found " + count + " valid music discs");
        }

        return result;
    }

    /**
     * Checks if the given item is a valid music disc
     *
     * @param disc The item to check
     * @return True if the item is a valid music disc
     */
    private boolean isValidMusicDisc(ItemStack disc) {
        if (disc == null) return false;

        String typeName = disc.getType().toString().toLowerCase();
        return typeName.contains("music_disc");
    }

    /**
     * Advances to the next valid disc and plays it
     */
    public void playNextDisc() {
        if (isPaused || discs == null) {
            plugin.logInfo("disc_player", "Not playing next disc: paused=" + isPaused + ", discs=" + (discs == null ? "null" : "notNull"));
            return;
        }

        // Prevent double advancement from callbacks
        if (acceleratedEnd) {
            plugin.logInfo("disc_player", "Ignoring playNextDisc call due to accelerated end flag");
            acceleratedEnd = false;
            return;
        }

        // Cancel any existing finish task
        if (this.discFinishTask != null) {
            this.discFinishTask.cancel();
            this.discFinishTask = null;
        }

        // If no valid discs, don't play anything
        if (!hasValidDiscs()) {
            isPlaying = false;
            currentDiscType = null;
            playStartTime = 0;
            playCountdown = 0;
            plugin.logInfo("disc_player", "No valid discs to play");
            return;
        }

        // Find the next valid disc
        int startIndex = currentIndex;
        boolean foundDisc = false;

        plugin.logInfo("disc_player", "Looking for next disc, starting from index " + startIndex);

        // First increment to next position
        currentIndex = (currentIndex + 1) % discs.length;

        // Then find a valid disc
        do {
            ItemStack disc = discs[currentIndex];

            if (isValidMusicDisc(disc)) {
                plugin.logInfo("disc_player", "Found valid disc at index " + currentIndex + ": " + disc.getType());
                playDisc(disc);
                foundDisc = true;
                break;
            }

            // If current disc isn't valid, move to next
            currentIndex = (currentIndex + 1) % discs.length;
            plugin.logInfo("disc_player", "Disc at index " + currentIndex + " is not valid, trying next");
        } while (currentIndex != startIndex); // Stop if we've checked all discs

        if (!foundDisc) {
            isPlaying = false;
            currentDiscType = null;
            playStartTime = 0;
            playCountdown = 0;
            plugin.logWarning("disc_player", "Couldn't find any valid discs despite hasValidDiscs returning true!");
        }
    }

    /**
     * Plays the given music disc at the jukebox location
     *
     * @param disc The music disc to play
     */
    private void playDisc(ItemStack disc) {
        if (isValidMusicDisc(disc)) {
            try {
                // First, stop any current sounds
                stopRecordSound();

                // Convert the disc type to the corresponding sound key
                Material discType = disc.getType();
                this.currentDiscType = discType;
                this.playStartTime = System.currentTimeMillis();
                this.acceleratedEnd = false;

                // Get the duration for this disc
                int discDuration = getDiscDuration(discType);
                this.playCountdown = discDuration;

                // Schedule a task to play the next disc when this one finishes
                this.discFinishTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (isPaused) return; // Don't count down while paused

                        playCountdown -= 20; // Decrease by 20 ticks (1 second)

                        // When the countdown reaches zero, play the next disc
                        if (playCountdown <= 0) {
                            plugin.logInfo("disc_player", "Disc " + currentDiscType + " finished playing (duration: " + (discDuration / 20) + " seconds), moving to next disc");

                            // Cancel this task (it will be recreated for the next disc)
                            if (discFinishTask != null) {
                                discFinishTask.cancel();
                                discFinishTask = null;
                            }

                            // Play the next disc
                            acceleratedEnd = true; // Prevent double advancement
                            playNextDisc();
                        }
                    }
                }, 20, 20); // Run every second (20 ticks)

                // Extract the disc name (e.g., "cat" from "MUSIC_DISC_CAT")
                String discName = discType.toString().toLowerCase().replace("music_disc_", "");

                // Format the proper sound key for Paper 1.21
                String recordsSound = "minecraft:record." + discName;
                String legacySound = "minecraft:music_disc." + discName;

                // Debug log before playing
                plugin.logInfo("disc_player", "Playing disc: " + discType + " with duration: " + (discDuration / 20) + " seconds at " + formatLocation(location));

                // Try both sound naming patterns for compatibility
                boolean played = false;

                // Try direct playback for all players in range
                for (Player player : location.getWorld().getPlayers()) {
                    if (player.getLocation().distance(location) <= JUKEBOX_RANGE) {
                        try {
                            // Try both sound formats
                            player.playSound(location, recordsSound, SoundCategory.RECORDS, 4.0f, 1.0f);
                            plugin.logInfo("disc_player", "Played record sound for player: " + player.getName());
                            played = true;
                        } catch (Exception e) {
                            try {
                                player.playSound(location, legacySound, SoundCategory.RECORDS, 4.0f, 1.0f);
                                plugin.logInfo("disc_player", "Played legacy sound for player: " + player.getName());
                                played = true;
                            } catch (Exception e2) {
                                plugin.logWarning("disc_player", "Failed to play both sound formats for " + player.getName());
                            }
                        }
                    }
                }

                // Fallback to world playSound if individual player method failed
                if (!played) {
                    try {
                        location.getWorld().playSound(location, recordsSound, SoundCategory.RECORDS, 4.0f, 1.0f);
                        plugin.logInfo("disc_player", "Played world sound using record format");
                    } catch (Exception e) {
                        try {
                            location.getWorld().playSound(location, legacySound, SoundCategory.RECORDS, 4.0f, 1.0f);
                            plugin.logInfo("disc_player", "Played world sound using legacy format");
                        } catch (Exception e2) {
                            throw new Exception("Both sound formats failed for world playback", e2);
                        }
                    }
                }

                // Also try to use Sound enum (as a fallback)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Get the raw Sound value from the Material type
                    try {
                        Sound discSound = null;
                        try {
                            discSound = Sound.valueOf("MUSIC_DISC_" + discName.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            // Try with just "RECORD_" prefix for legacy compatibility
                            try {
                                discSound = Sound.valueOf("RECORD_" + discName.toUpperCase());
                            } catch (IllegalArgumentException e2) {
                                plugin.logWarning("disc_player", "Could not find Sound enum for " + discName);
                            }
                        }

                        if (discSound != null) {
                            location.getWorld().playSound(location, discSound, SoundCategory.RECORDS, 4.0f, 1.0f);
                            plugin.logInfo("disc_player", "Played sound using Sound enum: " + discSound);
                        }
                    } catch (Exception e) {
                        plugin.logWarning("disc_player", "Error in sound enum fallback: " + e.getMessage());
                    }
                });

                // Set the playback state
                isPlaying = true;

                // Debug log after playing
                plugin.logInfo("disc_player", "Successfully played disc: " + disc.getType() + ", will play for " + (discDuration / 20) + " seconds");

            } catch (Exception e) {
                plugin.logError("disc_player", "Error playing disc: " + disc.getType(), e);
                isPlaying = false;
                currentDiscType = null;
                playStartTime = 0;
                playCountdown = 0;
                acceleratedEnd = false;
            }
        } else {
            plugin.logWarning("disc_player", "Attempted to play invalid disc: " + (disc == null ? "null" : disc.getType()));
        }
    }

    // Add these methods to DiscPlayer.java

    /**
     * Stops all music disc sounds for players within range of this jukebox
     */
    private void stopRecordSound() {
        try {
            plugin.logInfo("disc_player", "Stopping all music disc sounds at " + formatLocation(location));

            // Find all music disc materials
            List<String> musicDiscSoundKeys = new ArrayList<>();

            // Use the Material enum to find all music disc types
            for (Material material : Material.values()) {
                if (material.name().contains("MUSIC_DISC")) {
                    // Both naming patterns for compatibility
                    String discName = material.name().toLowerCase().replace("music_disc_", "");
                    musicDiscSoundKeys.add("minecraft:record." + discName);
                    musicDiscSoundKeys.add("minecraft:music_disc." + discName);
                }
            }

            // Only stop sounds for players within range
            for (Player player : location.getWorld().getPlayers()) {
                // Check if the player is within jukebox range
                if (player.getLocation().distance(location) <= JUKEBOX_RANGE) {
                    // Stop all music disc sounds for this player
                    for (String soundKey : musicDiscSoundKeys) {
                        player.stopSound(soundKey, SoundCategory.RECORDS);
                    }

                    // Also try to stop using the Sound enum
                    for (Sound sound : Sound.values()) {
                        if (sound.name().contains("MUSIC_DISC") || sound.name().contains("RECORD")) {
                            player.stopSound(sound, SoundCategory.RECORDS);
                        }
                    }
                }
            }

            // Reset the current disc type
            currentDiscType = null;
            playStartTime = 0;
            playCountdown = 0;
        } catch (Exception e) {
            plugin.logError("disc_playback", "Error stopping record sounds", e);
        }
    }

    /**
     * Checks if this player is currently playing music
     *
     * @return true if music is playing, false otherwise
     */
    public boolean isPlaying() {
        return isPlaying && !isPaused && currentDiscType != null;
    }

    /**
     * Gets the current playback info for debugging
     *
     * @return A string describing the current playback state
     */
    public String getPlaybackInfo() {
        if (!isPlaying || isPaused || currentDiscType == null) {
            return "Not playing";
        }

        long elapsedSeconds = (System.currentTimeMillis() - playStartTime) / 1000;
        long remainingSeconds = playCountdown / 20;

        return "Playing " + currentDiscType + " for " + elapsedSeconds + " seconds, " + remainingSeconds + " seconds remaining";
    }

    /**
     * Get the duration in ticks for a specific disc
     *
     * @param discType The disc material
     * @return Duration in ticks, or default 3 minutes (3600 ticks) if unknown
     */
    private int getDiscDuration(Material discType) {
        // Use our predefined durations or default to 3 minutes (3600 ticks)
        Integer duration = DISC_DURATIONS.get(discType);
        if (duration == null) {
            // For custom/unknown discs, use a default of 3 minutes
            duration = 3600; // 3 minutes in ticks

            // Add the unknown disc to config with default duration
            DiscPlayer.addUnknownDisc(plugin, discType, 180); // 3 minutes

            // Log the discovery
            plugin.logInfo("disc_durations", "Found unknown disc " + discType.name() + ", added to disc_durations.yml with default value: 180 seconds");
        } else {
            plugin.logInfo("disc_durations", "Duration for " + discType + ": " + (duration / 20) + " seconds");
        }
        return duration;
    }
}