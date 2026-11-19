package com.sk89q.worldedit.fabric;

import com.fastasyncworldedit.core.extent.inventory.SlottableBlockBag;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.extent.inventory.BlockBagException;
import com.sk89q.worldedit.extent.inventory.OutOfBlocksException;
import com.sk89q.worldedit.extent.inventory.OutOfSpaceException;
import com.sk89q.worldedit.extent.inventory.UnplaceableBlockException;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Supplier;

/** Player inventory access for slot patterns and inventory-backed edits. */
final class FabricPlayerBlockBag extends BlockBag implements SlottableBlockBag {
    private final Inventory inventory;
    private final MinecraftServer server;

    FabricPlayerBlockBag(Inventory inventory, MinecraftServer server) {
        this.inventory = inventory;
        this.server = server;
    }

    @Override
    public int size() {
        // Hotbar first, followed by main storage. Exclude equipment and offhand.
        return Inventory.INVENTORY_SIZE;
    }

    @Override
    public int getSelectedSlot() {
        return onServerThread(inventory::getSelectedSlot);
    }

    @Override
    public BaseItem getItem(int slot) {
        return onServerThread(() -> FabricAdapter.adapt(
            slot >= 0 && slot < size() ? inventory.getItem(slot) : ItemStack.EMPTY));
    }

    @Override
    public void setItem(int slot, BaseItem item) {
        if (slot < 0 || slot >= size()) {
            throw new IllegalArgumentException("Invalid inventory slot: " + slot);
        }
        onServerThread(() -> {
            int count = item instanceof BaseItemStack stack ? stack.getAmount() : 1;
            inventory.setItem(slot, FabricAdapter.adapt(
                new BaseItemStack(item.getType(), item.getNbtReference(), count)));
            inventory.setChanged();
            return null;
        });
    }

    @Override
    public void fetchBlock(BlockState block) throws BlockBagException {
        Item item = blockItem(block);
        boolean found = onServerThread(() -> {
            for (int slot = 0; slot < size(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && stack.is(item)) {
                    inventory.removeItem(slot, 1);
                    inventory.setChanged();
                    return true;
                }
            }
            return false;
        });
        if (!found) {
            throw new OutOfBlocksException();
        }
    }

    @Override
    public void storeBlock(BlockState block, int amount) throws BlockBagException {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative block amount: " + amount);
        }
        if (amount == 0) {
            return;
        }
        ItemStack incoming = new ItemStack(blockItem(block));
        boolean stored = onServerThread(() -> {
            // Check capacity before changing anything, so failure cannot duplicate
            // a partially stored stack when the caller handles OutOfSpaceException.
            long capacity = 0;
            for (int slot = 0; slot < size(); slot++) {
                capacity += spaceFor(inventory.getItem(slot), incoming);
            }
            if (capacity < amount) {
                return false;
            }
            int remaining = amount;
            for (int slot = 0; slot < size() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                int count = Math.min(remaining, spaceFor(stack, incoming));
                if (count == 0) {
                    continue;
                }
                if (stack.isEmpty()) {
                    inventory.setItem(slot, incoming.copyWithCount(count));
                } else {
                    stack.grow(count);
                }
                remaining -= count;
            }
            inventory.setChanged();
            return true;
        });
        if (!stored) {
            throw new OutOfSpaceException(block.getBlockType());
        }
    }

    private static int spaceFor(ItemStack stack, ItemStack incoming) {
        if (stack.isEmpty()) {
            return incoming.getMaxStackSize();
        }
        return ItemStack.isSameItemSameComponents(stack, incoming)
            ? Math.max(0, stack.getMaxStackSize() - stack.getCount()) : 0;
    }

    private static Item blockItem(BlockState block) throws UnplaceableBlockException {
        Item item = FabricAdapter.adapt(block.getBlockType()).asItem();
        if (item == Items.AIR) {
            throw new UnplaceableBlockException();
        }
        return item;
    }

    @Override
    public void flushChanges() {
        // Mutations are applied immediately on the server thread.
    }

    @Override
    public void addSourcePosition(Location position) {
        // This bag uses only the player's inventory, not nearby containers.
    }

    @Override
    public void addSingleSourcePosition(Location position) {
    }

    private <T> T onServerThread(Supplier<T> action) {
        if (server == null || server.isSameThread()) {
            return action.get();
        }
        return server.submit(action).join();
    }
}
