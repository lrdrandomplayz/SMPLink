package io.github.lrdrandomplayz.smplink.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bukkit.inventory.ItemStack;

/**
 * Packs an ItemStack[] into one BLOB. Each slot is a length prefix followed by
 * {@link ItemStack#serializeAsBytes()}, or -1 for an empty slot, because
 * serializeAsBytes refuses air.
 */
public final class InventoryCodec {

    private static final int FORMAT_VERSION = 1;

    private InventoryCodec() {
    }

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    public static byte[] encode(ItemStack[] items) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(items.length);
            for (ItemStack item : items) {
                if (isEmpty(item)) {
                    out.writeInt(-1);
                    continue;
                }
                byte[] data = item.serializeAsBytes();
                out.writeInt(data.length);
                out.write(data);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static ItemStack[] decode(byte[] data) {
        if (data == null) {
            return new ItemStack[0];
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unknown inventory format version " + version);
            }
            ItemStack[] items = new ItemStack[in.readInt()];
            for (int i = 0; i < items.length; i++) {
                int length = in.readInt();
                if (length < 0) {
                    continue;
                }
                byte[] item = new byte[length];
                in.readFully(item);
                items[i] = ItemStack.deserializeBytes(item);
            }
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
