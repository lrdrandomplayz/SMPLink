package io.github.lrdrandomplayz.smplink.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** One occupied slot, in the shape shared by the HTTP API, the Discord embeds and the inventory image. */
public record ItemView(int slot, String material, int amount, String displayName, Map<String, Integer> enchants,
                       int damage, int maxDamage) {

    /** Main thread only: reads item meta. */
    public static List<ItemView> describe(ItemStack[] items) {
        List<ItemView> views = new ArrayList<>();
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (!InventoryCodec.isEmpty(item)) {
                views.add(describe(slot, item));
            }
        }
        return views;
    }

    private static ItemView describe(int slot, ItemStack item) {
        String displayName = null;
        Map<String, Integer> enchants = new LinkedHashMap<>();
        int damage = 0;
        int maxDamage = item.getType().getMaxDurability();
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            Component name = meta.hasDisplayName() ? meta.displayName() : null;
            if (name != null) {
                displayName = PlainTextComponentSerializer.plainText().serialize(name);
            }
            addEnchants(enchants, meta.getEnchants());
            if (meta instanceof EnchantmentStorageMeta book) {
                addEnchants(enchants, book.getStoredEnchants());
            }
            if (meta instanceof Damageable damageable) {
                damage = damageable.getDamage();
                if (damageable.hasMaxDamage()) {
                    maxDamage = damageable.getMaxDamage();
                }
            }
        }
        return new ItemView(slot, item.getType().name(), item.getAmount(), displayName, enchants, damage, maxDamage);
    }

    private static void addEnchants(Map<String, Integer> target, Map<Enchantment, Integer> source) {
        source.forEach((enchantment, level) -> target.put(enchantment.getKey().getKey(), level));
    }
}
