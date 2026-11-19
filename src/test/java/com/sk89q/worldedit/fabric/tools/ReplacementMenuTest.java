package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.fabric.FabricInventoryPatternTest;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplacementMenuTest {
    @BeforeAll
    static void initialize() throws Exception {
        FabricInventoryPatternTest.initializeRegistries();
    }

    @Test
    void firstClickGlintsAndSecondDistinctClickSubmitsExactlyOnce() {
        List<ReplacementSelection.Request> requests = new ArrayList<>();
        AtomicInteger cancelled = new AtomicInteger();
        var menu = colors(requests, cancelled);
        click(menu, 0);
        assertTrue(menu.getContainer().getItem(0).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertTrue(menu.getContainer().getItem(22).getCustomName().getString().contains("Step 2"));
        assertTrue(requests.isEmpty());
        click(menu, 1);
        click(menu, 2);
        menu.removed(null);
        assertEquals(List.of(new ReplacementSelection.Request("white", "orange")), requests);
        assertEquals(0, cancelled.get());
        assertFalse(menu.stillValid(null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingBeforeCompletionCancelsWithoutSubmitting(boolean chooseSource) {
        List<ReplacementSelection.Request> requests = new ArrayList<>();
        AtomicInteger cancelled = new AtomicInteger();
        var menu = colors(requests, cancelled);
        if (chooseSource) click(menu, 0);
        menu.removed(null);
        menu.removed(null);
        click(menu, 1);
        assertTrue(requests.isEmpty());
        assertEquals(1, cancelled.get());
    }

    @Test
    void cancelButtonCancelsAndSelectingSourceAgainClearsItsGlint() {
        List<ReplacementSelection.Request> requests = new ArrayList<>();
        AtomicInteger cancelled = new AtomicInteger();
        var menu = colors(requests, cancelled);
        click(menu, 0);
        click(menu, 0);
        assertFalse(menu.getContainer().getItem(0).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertTrue(menu.getContainer().getItem(22).getCustomName().getString().contains("Step 1"));
        click(menu, 2);
        click(menu, 23); // Reset source.
        assertFalse(menu.getContainer().getItem(2).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        click(menu, 24); // Cancel.
        click(menu, 1);
        assertTrue(requests.isEmpty());
        assertEquals(1, cancelled.get());
    }

    @ParameterizedTest
    @EnumSource(value = ContainerInput.class, names = "PICKUP", mode = EnumSource.Mode.EXCLUDE)
    void transferGesturesNeitherCommitNorMoveInventoryItems(ContainerInput input) {
        List<ReplacementSelection.Request> requests = new ArrayList<>();
        AtomicInteger cancelled = new AtomicInteger();
        Inventory inventory = inventory();
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var menu = new ReplacementMenu(1, inventory, true, ReplacementChoices.colors(), requests::add, cancelled::incrementAndGet);
        click(menu, 0);
        menu.clicked(1, 0, input, null);
        for (int slot = 27; slot < menu.slots.size(); slot++) menu.clicked(slot, 0, input, null);
        assertTrue(requests.isEmpty());
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(7, inventory.getItem(0).getCount());
        assertEquals(Items.DIAMOND, inventory.getItem(0).getItem());
        assertEquals(1, menu.getContainer().getItem(0).getCount());
        assertTrue(menu.getContainer().getItem(0).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertTrue(menu.quickMoveStack(null, 0).isEmpty());
        assertFalse(menu.canDragTo(menu.getSlot(0)));
        assertFalse(menu.canTakeItemForPickAll(menu.getContainer().getItem(0), menu.getSlot(0)));
    }

    @Test
    void ordinaryClicksInPlayerInventoryOutsideAndRightClicksDoNothing() {
        List<ReplacementSelection.Request> requests = new ArrayList<>();
        AtomicInteger cancelled = new AtomicInteger();
        Inventory inventory = inventory();
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var menu = new ReplacementMenu(1, inventory, true, ReplacementChoices.colors(), requests::add, cancelled::incrementAndGet);
        menu.clicked(0, 1, ContainerInput.PICKUP, null);
        for (int slot = 27; slot < menu.slots.size(); slot++) click(menu, slot);
        click(menu, -999);
        assertTrue(requests.isEmpty());
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(7, inventory.getItem(0).getCount());
        assertFalse(menu.getContainer().getItem(0).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
    }

    @Test
    void sourcePersistsAcrossPagesAndModFilters() {
        List<ReplacementChoices.Choice> choices = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String namespace = i < 50 ? "a" : "b";
            choices.add(new ReplacementChoices.Choice(namespace + ":material_" + i, "Material " + i, namespace, Items.STONE));
        }
        List<ReplacementSelection.Request> requests = new ArrayList<>();
        var menu = new ReplacementMenu(1, inventory(), false, choices, requests::add, () -> fail("Committed menu must not cancel"));
        click(menu, 0);
        click(menu, 53); // Page 2.
        assertEquals("Material 45", menu.getContainer().getItem(0).getCustomName().getString());
        assertTrue(menu.getContainer().getItem(48).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        click(menu, 46); // Filter a.
        assertTrue(menu.getContainer().getItem(0).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        click(menu, 46); // Filter b.
        assertEquals("Material 50", menu.getContainer().getItem(0).getCustomName().getString());
        assertTrue(menu.getContainer().getItem(48).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertTrue(requests.isEmpty());
        click(menu, 0);
        assertEquals(List.of(new ReplacementSelection.Request("a:material_0", "b:material_50")), requests);
    }

    @Test
    void catalogsIncludeAllColorsAndRegisteredCrossModMaterialFamilies() {
        assertEquals(BlockFamilies.COLORS, ReplacementChoices.colors().stream().map(ReplacementChoices.Choice::key).toList());
        var materials = ReplacementChoices.materials();
        var keys = materials.stream().map(ReplacementChoices.Choice::key).toList();
        assertTrue(keys.contains("minecraft:oak"));
        assertTrue(keys.contains("buildertest:limestone"));
        assertTrue(keys.contains("otherbuilder:marble"));
        assertFalse(keys.contains("minecraft:air"));
        assertEquals(keys.size(), new HashSet<>(keys).size());
        assertEquals(Items.OAK_PLANKS, materials.stream().filter(choice -> choice.key().equals("minecraft:oak")).findFirst().orElseThrow().icon());
    }

    @Test
    void changedSelectionIsRejectedAndModelRejectsUnknownChoices() {
        var region = new CuboidRegion(BlockVector3.ZERO, BlockVector3.at(2, 2, 2));
        var snapshot = region.clone();
        assertTrue(ReplacementMenus.sameSelection(snapshot, region));
        region.shift(BlockVector3.at(1, 0, 0));
        assertFalse(ReplacementMenus.sameSelection(snapshot, region));
        var model = new ReplacementSelection(List.of("red", "blue"));
        assertTrue(model.pick("invalid").isEmpty());
        assertNull(model.source());
        model.pick("red");
        assertTrue(model.cancel());
        assertTrue(model.pick("blue").isEmpty());
    }

    private ReplacementMenu colors(List<ReplacementSelection.Request> requests, AtomicInteger cancelled) {
        return new ReplacementMenu(1, inventory(), true, ReplacementChoices.colors(), requests::add, cancelled::incrementAndGet);
    }

    private static Inventory inventory() { return new Inventory(null, new EntityEquipment()); }

    private static void click(ReplacementMenu menu, int slot) { menu.clicked(slot, 0, ContainerInput.PICKUP, null); }
}
