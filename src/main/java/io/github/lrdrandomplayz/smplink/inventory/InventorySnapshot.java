package io.github.lrdrandomplayz.smplink.inventory;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Slot layout shared by the live view and the cache: inventory slots 0-35 are
 * hotbar plus storage and slot 40 is the offhand (matching vanilla numbering),
 * armor is boots to helmet, ender chest is 0-26. Main thread only.
 */
public record InventorySnapshot(ItemStack[] inventory, ItemStack[] armor, ItemStack[] ender) {

    public static final int OFFHAND_SLOT = 40;

    public static InventorySnapshot of(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] main = new ItemStack[OFFHAND_SLOT + 1];
        ItemStack[] storage = inventory.getStorageContents();
        System.arraycopy(storage, 0, main, 0, Math.min(storage.length, 36));
        main[OFFHAND_SLOT] = inventory.getItemInOffHand();
        return new InventorySnapshot(main, inventory.getArmorContents(), player.getEnderChest().getContents());
    }
}
