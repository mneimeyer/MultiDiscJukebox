package org.neimeyer.multiDiscJukebox;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

public class JukeboxHolder implements InventoryHolder {
    private final Location loc;
    private final Inventory inventory;

    public JukeboxHolder(Location loc) {
        this.loc = loc;
        // Updated to use Component for inventory title
        this.inventory = Bukkit.createInventory(
                this,
                27,
                Component.text("MultiBox")
        );
    }

    public Location getLocation() {
        return loc;
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        return inventory;
    }

    // Optional: Method to set initial contents of the inventory
    public void setContents(ItemStack[] contents) {
        inventory.setContents(contents);
    }
}