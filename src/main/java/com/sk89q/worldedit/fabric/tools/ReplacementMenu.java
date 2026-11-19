package com.sk89q.worldedit.fabric.tools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A vanilla chest screen with display-only slots. No custom client screen is required. */
final class ReplacementMenu extends ChestMenu {
    private final Player owner;
    private final net.minecraft.world.level.Level openingLevel;
    private final boolean colors;
    private final int contentSize;
    private final List<ReplacementChoices.Choice> choices;
    private final List<String> namespaces;
    private final ReplacementSelection selection;
    private final Consumer<ReplacementSelection.Request> submit;
    private final Runnable onCancel;
    private List<ReplacementChoices.Choice> visible = List.of();
    private int namespaceIndex;
    private int page;

    ReplacementMenu(int id, Inventory inventory, boolean colors, List<ReplacementChoices.Choice> choices,
                    Consumer<ReplacementSelection.Request> submit, Runnable onCancel) {
        super(colors ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6, id, inventory,
            new SimpleContainer(colors ? 27 : 54), colors ? 3 : 6);
        this.owner = inventory.player;
        this.openingLevel = owner == null ? null : owner.level();
        this.colors = colors;
        this.contentSize = colors ? 18 : 45;
        this.choices = List.copyOf(choices);
        this.selection = new ReplacementSelection(choices.stream().map(ReplacementChoices.Choice::key).toList());
        this.submit = submit;
        this.onCancel = onCancel;
        List<String> mods = new ArrayList<>();
        mods.add("");
        choices.stream().map(ReplacementChoices.Choice::namespace).distinct().sorted().forEach(mods::add);
        this.namespaces = List.copyOf(mods);
        render();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        // Ignore every vanilla item-transfer gesture and stale/repeated packets.
        if (!stillValid(player)) return;
        if (input != ContainerInput.PICKUP || button != 0) {
            broadcastFullState();
            return;
        }
        String previousSource = selection.source();
        if (slot >= 0 && slot < contentSize && slot < visible.size()) {
            var request = selection.pick(visible.get(slot).key());
            if (request.isPresent()) {
                closeForOwner();
                submit.accept(request.get());
                return;
            }
        } else if (slot == contentSize) {
            page = Math.max(0, page - 1);
        } else if (slot == contentSize + 8) {
            page = Math.min(pageCount() - 1, page + 1);
        } else if (slot == contentSize + 1 && !colors) {
            namespaceIndex = (namespaceIndex + 1) % namespaces.size();
            page = 0;
        } else if (slot == contentSize + 5 || slot == contentSize + 3) {
            selection.clear();
        } else if (slot == contentSize + 6) {
            cancel();
            closeForOwner();
            return;
        }
        render();
        broadcastFullState();
        if (!java.util.Objects.equals(previousSource, selection.source()) && owner instanceof ServerPlayer viewer) {
            String message = selection.source() == null ? "Source cleared. Choose the source."
                : "Source selected. Choose a replacement, or close to cancel.";
            viewer.sendOverlayMessage(Component.literal(message));
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }
    @Override public boolean stillValid(Player player) {
        return player == owner && !selection.finished()
            && (player == null || player.isAlive() && player.level() == openingLevel);
    }

    @Override
    public void removed(Player player) {
        if (player == owner) cancel();
        super.removed(player);
    }

    private void closeForOwner() {
        if (owner instanceof ServerPlayer player && player.containerMenu == this) player.closeContainer();
    }

    private void cancel() {
        if (selection.cancel()) onCancel.run();
    }

    private List<ReplacementChoices.Choice> filtered() {
        String namespace = namespaces.get(namespaceIndex);
        return namespace.isEmpty() ? choices : choices.stream().filter(choice -> choice.namespace().equals(namespace)).toList();
    }

    private int pageCount() { return Math.max(1, (filtered().size() + contentSize - 1) / contentSize); }

    private void render() {
        var inventory = getContainer();
        inventory.clearContent();
        List<ReplacementChoices.Choice> filtered = filtered();
        page = Math.clamp(page, 0, pageCount() - 1);
        int start = page * contentSize;
        visible = filtered.subList(start, Math.min(start + contentSize, filtered.size()));
        String source = selection.source();
        for (int slot = 0; slot < visible.size(); slot++) {
            var choice = visible.get(slot);
            boolean selected = choice.key().equals(source);
            inventory.setItem(slot, display(choice.icon(), choice.label(), selected,
                choice.key(), selected ? "Selected source. Click again to clear."
                    : source == null ? "Click to choose the source." : "Click to choose the replacement and apply."));
        }
        if (page > 0) inventory.setItem(contentSize, display(Items.ARROW, "Previous page", false));
        if (page + 1 < pageCount()) inventory.setItem(contentSize + 8, display(Items.ARROW, "Next page", false));
        if (!colors) inventory.setItem(contentSize + 1, display(Items.COMPASS,
            "Mod: " + (namespaceIndex == 0 ? "All mods" : namespaces.get(namespaceIndex)), false,
            "Click to cycle through mods.", "The selected source stays selected."));
        inventory.setItem(contentSize + 2, display(Items.PAPER, "How to replace", false,
            "1. Click the source. It will glow.", "2. Click a different replacement to apply.", "Close this menu to cancel.", "Inventory items cannot be moved here."));
        inventory.setItem(contentSize + 4, display(Items.MAP,
            source == null ? "Step 1: Choose source" : "Step 2: Choose replacement", false,
            "Page " + (page + 1) + " / " + pageCount(), "Closing this menu cancels."));
        if (source != null) {
            var from = choices.stream().filter(choice -> choice.key().equals(source)).findFirst().orElseThrow();
            inventory.setItem(contentSize + 3, display(from.icon(), "Source: " + from.label(), true,
                from.key(), "Click to clear the source."));
            inventory.setItem(contentSize + 5, display(Items.REDSTONE, "Reset source", false));
        }
        inventory.setItem(contentSize + 6, display(Items.BARRIER, "Cancel replacement", false,
            "Close without changing any blocks."));
    }

    private static ItemStack display(Item item, String title, boolean selected, String... lines) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title)
            .withStyle(style -> style.withItalic(false).withColor(selected ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, selected);
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lines)
            .map(line -> (Component) Component.literal(line).withStyle(ChatFormatting.GRAY)).toList()));
        return stack;
    }
}
