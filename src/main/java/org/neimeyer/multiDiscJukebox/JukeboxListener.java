package org.neimeyer.multiDiscJukebox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class JukeboxListener implements Listener {
    private final MultiDiscJukebox plugin;
    private final NamespacedKey cyclerKey;

    // Track events that have been handled
    // Key is player UUID + block position hash, value is timestamp
    private final Map<String, Long> handledEvents = new HashMap<>();

    public JukeboxListener(MultiDiscJukebox plugin) {
        this.plugin = plugin;
        this.cyclerKey = plugin.getCyclerKey();
    }

    public static boolean isPlayerHead(Block block) {
        return block != null && block.getType() == Material.PLAYER_HEAD;
    }

    public static void dropDiscItems(Location loc, ItemStack[] discs, World world) {
        if (discs != null) {
            Arrays.stream(discs).filter(Objects::nonNull).forEach(disc -> world.dropItemNaturally(loc, disc));
        }
    }

    public static boolean isCyclerJukebox(Skull skull, NamespacedKey cyclerKey) {
        return skull != null && skull.getPersistentDataContainer().has(cyclerKey, PersistentDataType.BYTE);
    }

    public static String locationToString(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private boolean isMusicDisc(Material material) {
        return material != null && material.name().contains("MUSIC_DISC");
    }

    // Generate a unique key for tracking an interaction event
    private String getEventKey(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return null;

        return event.getPlayer().getUniqueId() + ":" + event.getClickedBlock().getLocation().hashCode() + ":" + System.currentTimeMillis() / 50; // 50 ms resolution to batch nearby events
    }

    // Check if an event was recently handled
    private boolean wasEventHandled(PlayerInteractEvent event) {
        String key = getEventKey(event);
        if (key == null) return false;

        Long timestamp = handledEvents.get(key);
        if (timestamp == null) return false;

        // Consider events handled for 100ms (2 ticks)
        return System.currentTimeMillis() - timestamp < 100;
    }

    // Mark an event as handled
    private void markEventAsHandled(PlayerInteractEvent event) {
        String key = getEventKey(event);
        if (key != null) {
            handledEvents.put(key, System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        // Check if this event has already been handled
        if (wasEventHandled(event)) {
            event.setCancelled(true);  // Make sure it stays canceled
            return;
        }

        // only care about right‐clicks on blocks
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (!isPlayerHead(clicked)) {
            return;
        }

        Skull skull = (Skull) clicked.getState();
        if (!isCyclerJukebox(skull, cyclerKey)) {
            return;
        }

        // If holding a music disc, let onInteractWithDisc handle it
        ItemStack inHand = event.getItem();
        if (inHand != null && isMusicDisc(inHand.getType())) {
            // We'll let the HIGH priority handler deal with this
            return;
        }

        // Open inventory GUI only if not holding a music disc
        Location loc = skull.getLocation();
        JukeboxHolder holder = new JukeboxHolder(loc);
        ItemStack[] previous = plugin.getInventories().get(loc);
        if (previous != null) holder.setContents(previous);

        event.getPlayer().openInventory(holder.getInventory());

        // now it's safe to cancel – right‐click won't break the block
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // only care about our custom MultiBox UI
        if (!(event.getInventory().getHolder() instanceof JukeboxHolder holder)) {
            return;
        }

        Location loc = holder.getLocation();
        ItemStack[] contents = event.getInventory().getContents();

        // Persist the new contents in your map
        plugin.getInventories().put(loc, contents);

        // Create or update the DiscPlayer so playback actually happens
        DiscPlayer dp = plugin.getDiscPlayers().get(loc);
        if (dp == null) {
            // make and start it
            dp = new DiscPlayer(plugin, loc, contents);
            plugin.getDiscPlayers().put(loc, dp);
            dp.start();
        } else {
            // already playing: just tell it about the new set of discs
            dp.updateDiscs(contents);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractWithDisc(PlayerInteractEvent event) {
        // Only handle right-click with item on block
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (!isPlayerHead(clicked)) {
            return;
        }

        Skull skull = (Skull) clicked.getState();
        if (!isCyclerJukebox(skull, plugin.getCyclerKey())) {
            return;
        }

        // Check if the player is holding a music disc
        Player player = event.getPlayer();
        ItemStack inHand = event.getItem();

        if (inHand != null && isMusicDisc(inHand.getType())) {
            // Mark this event as handled to prevent the normal interaction handler from running
            markEventAsHandled(event);

            // Cancel the event first to prevent the inventory from opening
            event.setCancelled(true);

            // Debug logging
            plugin.logInfo("block_events", "Processing disc interaction from player " + player.getName() + " with disc: " + inHand.getType());

            Location loc = clicked.getLocation();
            ItemStack[] contents = plugin.getInventories().get(loc);

            // If no inventory exists yet, create one
            if (contents == null) {
                contents = new ItemStack[27];
                plugin.getInventories().put(loc, contents);
            }

            // Try to find an empty slot for the disc
            boolean added = false;
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] == null) {
                    // Add a copy of the disc (with amount 1)
                    ItemStack disc = inHand.clone();
                    disc.setAmount(1);
                    contents[i] = disc;
                    added = true;
                    plugin.logInfo("block_events", "Added disc " + disc.getType() + " to slot " + i);
                    break;
                }
            }

            if (added) {
                // Decrement the disc in the player's hand
                if (inHand.getAmount() > 1) {
                    inHand.setAmount(inHand.getAmount() - 1);
                } else {
                    // Set to null if it was the last item
                    player.getInventory().setItemInMainHand(null);
                }

                // Update the jukebox player
                DiscPlayer dp = plugin.getDiscPlayers().get(loc);
                if (dp == null) {
                    // Create a new player if needed
                    plugin.logInfo("disc_player", "Creating new DiscPlayer for location: " + loc);
                    dp = new DiscPlayer(plugin, loc, contents);
                    plugin.getDiscPlayers().put(loc, dp);
                    dp.start();
                } else {
                    // Update discs in existing player
                    plugin.logInfo("disc_player", "Updating existing DiscPlayer with new disc contents");
                    dp.updateDiscs(contents);
                }

                // Force play if not already playing
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    DiscPlayer checkPlayer = plugin.getDiscPlayers().get(loc);
                    if (checkPlayer != null && !checkPlayer.isPlaying()) {
                        plugin.logInfo("disc_player", "Force-starting playback after adding disc");
                        checkPlayer.playNextDisc();
                    }
                }, 10L); // Short delay

                // Provide feedback
                player.sendMessage(Component.text("Added disc to MultiBox").color(NamedTextColor.GOLD));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);

                // Spawn particle effect at the MultiBox to indicate success
                loc.getWorld().spawnParticle(Particle.NOTE, loc.clone().add(0.5, 1.0, 0.5), 5, 0.5, 0.5, 0.5, 1);
            } else {
                player.sendMessage(Component.text("MultiBox is full. Open it to manage discs.").color(NamedTextColor.RED));
            }
        }
    }

    // Clean up old handled events periodically to prevent memory leaks
    public void cleanupHandledEvents() {
        long now = System.currentTimeMillis();
        handledEvents.entrySet().removeIf(entry -> now - entry.getValue() > 10000); // Remove entries older than 10 seconds
    }

    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.PLAYER_HEAD) return;
        Skull skull = (Skull) block.getState();
        if (!isCyclerJukebox(skull, cyclerKey)) return;

        DiscPlayer dp = plugin.getDiscPlayers().get(block.getLocation());
        if (dp != null) {
            dp.setPaused(event.getNewCurrent() > 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Check if the block is a Multibox (i.e., a player head with the correct key)
        if (!isPlayerHead(block)) return;
        Skull skull = (Skull) block.getState();
        if (!isCyclerJukebox(skull, cyclerKey)) return;

        // Allow breaking and prevent drops
        event.setCancelled(false);
        event.setDropItems(false);

        Location loc = block.getLocation();

        // Stop any active disc players
        DiscPlayer dp = plugin.getDiscPlayers().remove(loc);
        if (dp != null) {
            dp.stop();
            plugin.logInfo("disc_player", "Stopped DiscPlayer at " + JukeboxListener.locationToString(loc));
        }

        // Drop associated inventory items
        ItemStack[] discs = plugin.getInventories().remove(loc);
        dropDiscItems(loc, discs, block.getWorld());

        // Drop the Multibox item itself
        block.getWorld().dropItemNaturally(loc, plugin.getMultiBoxItem());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent ev) {
        // Check if it's our MultiBox
        ItemStack inHand = ev.getItemInHand();
        ItemMeta meta = inHand.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(cyclerKey, PersistentDataType.BYTE)) {
            return;
        }

        // Check if it's being placed as a player head
        Block placed = ev.getBlockPlaced();
        if (placed.getType() != Material.PLAYER_HEAD && placed.getType() != Material.PLAYER_WALL_HEAD) {
            return;
        }

        // Store the cycler key in the skull's PersistentDataContainer
        if (placed.getState() instanceof Skull skull) {
            skull.getPersistentDataContainer().set(cyclerKey, PersistentDataType.BYTE, (byte) 1);
            skull.update();
        }

        // Initialize the inventory for this location
        plugin.getInventories().put(placed.getLocation(), new ItemStack[27]);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        ev.getPlayer().discoverRecipe(plugin.getMultiboxKey());
    }
}