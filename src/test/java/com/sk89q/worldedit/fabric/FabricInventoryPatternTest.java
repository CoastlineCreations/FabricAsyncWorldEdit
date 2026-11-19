package com.sk89q.worldedit.fabric;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.LocalConfiguration;
import com.fastasyncworldedit.core.limit.FaweLimit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.factory.parser.DefaultBlockParser;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extent.inventory.OutOfBlocksException;
import com.sk89q.worldedit.extent.inventory.OutOfSpaceException;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.io.ResourceLoader;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.registry.ItemRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class FabricInventoryPatternTest {
    private static boolean initialized;
    private Inventory inventory;
    private FabricPlayerBlockBag bag;
    private ParserContext context;
    private final AtomicReference<com.sk89q.worldedit.world.block.BlockState> maskBlock = new AtomicReference<>();

    @BeforeAll
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized void initializeRegistries() throws Exception {
        if (initialized) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerTestBlocks();
        // Server data loading normally binds these prototypes. These tests need
        // only stack capacity, not the full datapack-dependent item defaults.
        BuiltInRegistries.ITEM.listElements().forEach(holder -> holder.bindComponents(
            DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()));

        // Use real native registries without starting a Minecraft server. The
        // platform's registry cache also serves normal server shutdown reads.
        var registryCache = FabricWorldEdit.class.getDeclaredField("REGISTRY_CACHE");
        registryCache.setAccessible(true);
        Map cache = (Map) registryCache.get(null);
        cache.put(Registries.BLOCK, BuiltInRegistries.BLOCK);
        cache.put(Registries.ITEM, BuiltInRegistries.ITEM);
        cache.put(Registries.BLOCK_ENTITY_TYPE, BuiltInRegistries.BLOCK_ENTITY_TYPE);

        var registries = new FabricRegistries() {
            @Override
            public ItemRegistry getItemRegistry() {
                return new FabricItemRegistry() {
                    @Override
                    public Collection<String> values() {
                        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).toList();
                    }
                };
            }
        };
        ResourceLoader resourceLoader = path -> Path.of("inventory-test", path);
        LocalConfiguration configuration = new LocalConfiguration() {
            @Override
            public void load() {
            }

            @Override
            public Path getWorkingDirectoryPath() {
                return Path.of("inventory-test");
            }
        };
        Platform platform = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
            new Class<?>[]{Platform.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getRegistries" -> registries;
                case "getConfiguration" -> configuration;
                case "getResourceLoader" -> resourceLoader;
                case "getDataVersion" -> SharedConstants.getCurrentVersion().dataVersion().version();
                case "versionMinY" -> -64;
                case "versionMaxY" -> 319;
                case "toString" -> "Inventory pattern test platform";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        // Bypass game lifecycle hooks; this fixture only exercises FAWE's real
        // parser and registry caches against a native Minecraft inventory.
        var manager = WorldEdit.getInstance().getPlatformManager();
        var preferences = manager.getClass().getDeclaredField("preferences");
        preferences.setAccessible(true);
        Map capabilities = (Map) preferences.get(manager);
        capabilities.put(Capability.GAME_HOOKS, platform);
        capabilities.put(Capability.WORLD_EDITING, platform);
        capabilities.put(Capability.CONFIGURATION, platform);
        var initialized = manager.getClass().getDeclaredField("initialized");
        initialized.setAccessible(true);
        ((AtomicBoolean) initialized.get(manager)).set(true);
        WorldEdit.getInstance().getMaskFactory().register(new FabricInventoryMaskParser(WorldEdit.getInstance()));
        FabricInventoryPatternTest.initialized = true;
    }

    private static void registerTestBlocks() throws Exception {
        // Simulate Fabric mods before WorldEdit builds its block-state cache.
        var registry = BuiltInRegistries.BLOCK;
        var frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
        var intrusive = net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
        frozen.setAccessible(true); intrusive.setAccessible(true);
        frozen.set(registry, false); intrusive.set(registry, new java.util.IdentityHashMap<>());
        try {
            for (String name : java.util.List.of("buildertest:red_panel", "buildertest:blue_panel",
                "buildertest:copper_panel", "buildertest:waxed_copper_panel", "buildertest:limestone_stairs",
                "otherbuilder:marble_stairs", "ambiguous_a:basaltic_stairs", "ambiguous_b:basaltic_stairs")) {
                var id = net.minecraft.resources.Identifier.parse(name);
                var properties = net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                    .setId(net.minecraft.resources.ResourceKey.create(Registries.BLOCK, id));
                net.minecraft.world.level.block.Block block = name.endsWith("_stairs")
                    ? new net.minecraft.world.level.block.StairBlock(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), properties) { }
                    : new net.minecraft.world.level.block.Block(properties);
                net.minecraft.core.Registry.register(registry, id, block);
                var holder = registry.wrapAsHolder(block);
                var bindTags = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
                bindTags.setAccessible(true); bindTags.invoke(holder, java.util.List.of());
                ((net.minecraft.core.Holder.Reference<?>) holder).bindComponents(DataComponentMap.EMPTY);
                block.getStateDefinition().getPossibleStates().forEach(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY::add);
            }
        } finally {
            // Keep the already-bootstrapped vanilla tags; freeze() would try to
            // bootstrap them again. New intrusive holders were bound above.
            frozen.set(registry, true); intrusive.set(registry, null);
        }
    }

    @BeforeEach
    void createInventory() {
        inventory = new Inventory(null, new EntityEquipment());
        bag = new FabricPlayerBlockBag(inventory, null);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getInventoryBlockBag" -> bag;
                case "getBlockInHand" -> FabricPlayer.blockInHand(
                    args[0] == HandSide.MAIN_HAND ? inventory.getSelectedItem()
                        : new ItemStack(Items.GLASS));
                case "getItemInHand" -> FabricAdapter.adapt(inventory.getSelectedItem());
                case "hasPermission", "isPlayer" -> true;
                case "getName" -> "InventoryTest";
                case "getLimit" -> FaweLimit.MAX.copy();
                case "toString" -> "Inventory test player";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        context = new ParserContext();
        context.setActor(player);
        maskBlock.set(BlockTypes.AIR.getDefaultState());
        context.setExtent(new NullExtent() {
            @Override
            public com.sk89q.worldedit.world.block.BlockState getBlock(BlockVector3 position) {
                return maskBlock.get();
            }
        });
        context.setRestricted(false);
        context.setTryLegacy(false);
        context.setPreferringWildcard(false);
    }

    @Test
    void slotOneReadsFirstHotbarSlotWithoutConsumingItems() throws Exception {
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(1, new ItemStack(Items.DIRT, 8));
        inventory.setSelectedSlot(1);
        assertPattern("slot1", "minecraft:stone");
        assertPattern("slot2", "minecraft:dirt");
        assertPattern("hand", "minecraft:dirt");
        assertPattern("offhand", "minecraft:glass");
        assertEquals(12, inventory.getItem(0).getCount());
        assertEquals(8, inventory.getItem(1).getCount());
    }

    @Test
    void itemAndBlockIdsCanDiffer() throws Exception {
        inventory.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 5));
        assertPattern("slot1", "minecraft:wheat");
        assertPattern("hand", "minecraft:wheat");
        assertEquals("minecraft:wheat_seeds", bag.getItem(0).getType().id());
    }

    @ParameterizedTest(name = "slot{0} works as a pattern and source mask")
    @MethodSource("inventorySlots")
    void everyInventorySlotWorksInReplacementArguments(int slotNumber) throws Exception {
        // Different blocks in every slot expose indexing mistakes at both hotbar
        // and storage boundaries, rather than testing an otherwise empty bag.
        var blocks = BuiltInRegistries.ITEM.stream()
            .filter(item -> item instanceof BlockItem).map(item -> (BlockItem) item)
            .limit(36).toList();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, new ItemStack(blocks.get(slot), slot + 1));
        }
        var expected = FabricAdapter.adapt(blocks.get(slotNumber - 1).getBlock().defaultBlockState());
        var pattern = WorldEdit.getInstance().getPatternFactory().parseFromInput("slot" + slotNumber, context);
        assertEquals(expected.getBlockType(), pattern.applyBlock(BlockVector3.ZERO).getBlockType());

        var mask = WorldEdit.getInstance().getMaskFactory().parseFromInput("slot" + slotNumber, context);
        maskBlock.set(expected);
        assertTrue(mask.test(BlockVector3.ZERO), () -> "slot" + slotNumber + ": " + expected + " via " + mask);
        maskBlock.set(BlockTypes.AIR.getDefaultState());
        assertFalse(mask.test(BlockVector3.ZERO));

        for (int slot = 0; slot < 36; slot++) {
            assertEquals(slot + 1, inventory.getItem(slot).getCount());
        }
    }

    static IntStream inventorySlots() {
        return IntStream.rangeClosed(1, 36);
    }

    @Test
    void hotbarPatternAndMaskIncludeAllNineSlotsAndExcludeMainStorage() throws Exception {
        var items = new net.minecraft.world.item.Item[]{Items.STONE, Items.DIRT, Items.GLASS,
            Items.OAK_PLANKS, Items.GOLD_BLOCK, Items.IRON_BLOCK, Items.EMERALD_BLOCK,
            Items.DIAMOND_BLOCK, Items.REDSTONE_BLOCK};
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, new ItemStack(items[slot], slot + 1));
        }
        inventory.setItem(9, new ItemStack(Items.OBSIDIAN, 20));
        Set<String> expected = java.util.Arrays.stream(items)
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).collect(Collectors.toSet());
        assertEquals(expected, hotbarPatternBlocks());

        var mask = WorldEdit.getInstance().getMaskFactory().parseFromInput("#hotbar", context);
        for (var item : items) {
            maskBlock.set(FabricAdapter.adapt(((BlockItem) item).getBlock().defaultBlockState()));
            assertTrue(mask.test(BlockVector3.ZERO));
        }
        maskBlock.set(BlockTypes.OBSIDIAN.getDefaultState());
        assertFalse(mask.test(BlockVector3.ZERO));
        maskBlock.set(BlockTypes.AIR.getDefaultState());
        assertFalse(mask.test(BlockVector3.ZERO));
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(slot + 1, inventory.getItem(slot).getCount());
        }
        assertEquals(20, inventory.getItem(9).getCount());
    }

    @Test
    void hotbarSkipsToolsAndEmptySlotsAndUsesNativeBlockMappings() throws Exception {
        inventory.setItem(0, new ItemStack(Items.STONE));
        inventory.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
        inventory.setItem(8, new ItemStack(Items.WHEAT_SEEDS));
        assertEquals(Set.of("minecraft:stone", "minecraft:wheat"), hotbarPatternBlocks());

        var mask = WorldEdit.getInstance().getMaskFactory().parseFromInput("#hotbar", context);
        maskBlock.set(BlockTypes.WHEAT.getDefaultState());
        assertTrue(mask.test(BlockVector3.ZERO));
        maskBlock.set(BlockTypes.AIR.getDefaultState());
        assertFalse(mask.test(BlockVector3.ZERO));
    }

    @Test
    void hotbarWithoutBlockItemsProducesParseErrors() {
        inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        inventory.setItem(9, new ItemStack(Items.STONE));
        assertThrows(InputParseException.class, () ->
            WorldEdit.getInstance().getPatternFactory().parseFromInput("#hotbar", context));
        assertThrows(InputParseException.class, () ->
            WorldEdit.getInstance().getMaskFactory().parseFromInput("#hotbar", context));
    }

    @Test
    void inventoryMasksPreserveExplicitStatesAndComposition() throws Exception {
        inventory.setItem(8, new ItemStack(Items.OAK_STAIRS));
        var stateMask = WorldEdit.getInstance().getMaskFactory().parseFromInput("slot9[facing=west]", context);
        var west = net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                net.minecraft.core.Direction.WEST)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                net.minecraft.world.level.block.state.properties.Half.TOP);
        maskBlock.set(FabricAdapter.adapt(west));
        assertTrue(stateMask.test(BlockVector3.ZERO));
        maskBlock.set(FabricAdapter.adapt(west.setValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
            net.minecraft.core.Direction.EAST)));
        assertFalse(stateMask.test(BlockVector3.ZERO));

        inventory.setItem(0, new ItemStack(Items.STONE));
        inventory.setItem(35, new ItemStack(Items.GLASS));
        var combined = WorldEdit.getInstance().getMaskFactory().parseFromInput("slot1,slot36", context);
        maskBlock.set(BlockTypes.STONE.getDefaultState());
        assertTrue(combined.test(BlockVector3.ZERO));
        maskBlock.set(BlockTypes.GLASS.getDefaultState());
        assertTrue(combined.test(BlockVector3.ZERO));
        maskBlock.set(BlockTypes.DIRT.getDefaultState());
        assertFalse(combined.test(BlockVector3.ZERO));
    }

    private Set<String> hotbarPatternBlocks() throws InputParseException {
        var pattern = assertInstanceOf(RandomPattern.class,
            WorldEdit.getInstance().getPatternFactory().parseFromInput("#hotbar", context));
        return pattern.getPatterns().stream()
            .map(block -> block.applyBlock(BlockVector3.ZERO).getBlockType().id()).collect(Collectors.toSet());
    }

    @Test
    void emptyAndInvalidSlotsProduceParseErrors() {
        assertThrows(InputParseException.class, () -> parse("slot0"));
        assertThrows(InputParseException.class, () -> parse("slot37"));
        assertThrows(InputParseException.class, () -> parse("slot2147483647"));
        assertThrows(InputParseException.class, () -> parse("slot1"));
        inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        assertThrows(InputParseException.class, () -> parse("slot1"));
    }

    @Test
    void fetchingConsumesExactlyOneBlockAndReportsExhaustion() throws Exception {
        inventory.setItem(0, new ItemStack(Items.STONE, 1));
        bag.fetchBlock(BlockTypes.STONE.getDefaultState());
        assertTrue(inventory.getItem(0).isEmpty());
        assertThrows(OutOfBlocksException.class, () -> bag.fetchBlock(BlockTypes.STONE.getDefaultState()));
    }

    @Test
    void storingSplitsStacksAndDoesNotPartiallyStoreOnOverflow() throws Exception {
        inventory.setItem(0, new ItemStack(Items.STONE, 63));
        bag.storeBlock(BlockTypes.STONE.getDefaultState(), 3);
        assertEquals(64, inventory.getItem(0).getCount());
        assertEquals(2, inventory.getItem(1).getCount());
        for (int slot = 1; slot < bag.size(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        inventory.setItem(0, new ItemStack(Items.STONE, 63));
        assertThrows(OutOfSpaceException.class, () -> bag.storeBlock(BlockTypes.STONE.getDefaultState(), 2));
        assertEquals(63, inventory.getItem(0).getCount());
    }

    private void assertPattern(String pattern, String expectedBlock) throws Exception {
        assertEquals(expectedBlock, parse(pattern).getBlockType().id());
    }

    private com.sk89q.worldedit.world.block.BaseBlock parse(String pattern) throws InputParseException {
        return new DefaultBlockParser(WorldEdit.getInstance()).parseFromInput(pattern, context);
    }
}
