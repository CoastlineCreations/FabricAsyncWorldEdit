package com.sk89q.worldedit.fabric;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.fabric.tools.*;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuilderToolsTest {
    private MemoryExtent world;
    private ParserContext context;
    private BlockFamilies families;

    @BeforeAll static void initialize() throws Exception {
        FabricInventoryPatternTest.initializeRegistries();
        BuilderParsers.register(WorldEdit.getInstance());
    }

    @BeforeEach void createWorld() {
        world=new MemoryExtent(); context=new ParserContext(); context.setExtent(world); context.setRestricted(false);
        families=BlockFamilies.live();
    }

    @Test void colorChangesOnlyRegisteredCounterparts() {
        assertEquals("minecraft:blue_wool",families.recolor(block("red_wool"),"blue").getBlockType().id());
        assertEquals("buildertest:blue_panel",families.recolor(block("buildertest:red_panel"),"blue").getBlockType().id());
        assertEquals(block("stone"),families.recolor(block("stone"),"blue"));
    }

    @Test void crossModTypeReplacementPreservesStairState() throws Exception {
        var state=WorldEdit.getInstance().getBlockFactory().parseFromInput("buildertest:limestone_stairs[facing=west,half=top]",context);
        var result=families.retype(state,"limestone","marble");
        assertEquals("otherbuilder:marble_stairs",result.getBlockType().id());
        assertEquals((Object)state.getState(state.getBlockType().getProperty("facing")),result.getState(result.getBlockType().getProperty("facing")));
        assertEquals("top",result.getState(result.getBlockType().getProperty("half")));
        assertEquals(state,families.retype(state,"limestone","basaltic"));
        assertEquals("ambiguous_a:basaltic_stairs",families.retype(state,"limestone","ambiguous_a:basaltic").getBlockType().id());
    }

    @Test void vanillaFamilyShapesAndAliasesWork() {
        assertEquals("minecraft:cobblestone",families.retype(block("oak_planks"),"oak","cobble").getBlockType().id());
        assertEquals("minecraft:cobblestone_stairs",families.retype(block("oak_stairs"),"oak","cobble").getBlockType().id());
        assertEquals("minecraft:cobblestone_wall",families.retype(block("oak_fence"),"oak","cobble").getBlockType().id());
        assertEquals(block("birch_stairs"),families.retype(block("birch_stairs"),"oak","cobble"));
        assertEquals(block("dark_oak_stairs"),families.retype(block("dark_oak_stairs"),"oak","cobble"));
    }

    @Test void waxSupportsNativeAndModdedCounterparts() {
        assertEquals("minecraft:waxed_exposed_copper",families.wax(block("exposed_copper")).getBlockType().id());
        assertEquals("buildertest:waxed_copper_panel",families.wax(block("buildertest:copper_panel")).getBlockType().id());
        assertEquals(block("stone"),families.wax(block("stone")));
        assertEquals(block("waxed_copper_block"),families.wax(block("waxed_copper_block")));
    }

    @Test void recolorKeepsCompatibleBlockEntityData() {
        var tag=new net.minecraft.nbt.CompoundTag(); tag.putString("id","minecraft:shulker_box"); tag.putString("CustomName","test");
        BaseBlock original=BlockTypes.RED_SHULKER_BOX.getDefaultState().toBaseBlock(
            com.sk89q.worldedit.util.concurrency.LazyReference.computed(com.sk89q.worldedit.fabric.internal.NBTConverter.fromNative(tag)));
        var result=families.recolor(original,"blue");
        assertEquals("minecraft:blue_shulker_box",result.getBlockType().id());
        assertEquals(original.getNbt(),result.getNbt());
        assertNull(BlockFamilies.transfer(original,BlockTypes.STONE).getNbt());
    }

    @Test void replacementStaysInsideSelectionAndReportsChanges() throws Exception {
        world.setBlock(BlockVector3.at(0,0,0),block("red_wool")); world.setBlock(BlockVector3.at(1,0,0),block("red_wool"));
        int count=BuilderOperations.replace(world,new CuboidRegion(BlockVector3.ZERO,BlockVector3.ZERO),b -> families.recolor(b,"blue"));
        assertEquals(1,count); assertEquals(block("blue_wool"),world.getFullBlock(BlockVector3.ZERO));
        assertEquals(block("red_wool"),world.getFullBlock(BlockVector3.at(1,0,0)));
    }

    @Test void familyPatternsAndMasksUseLiveModdedRegistry() throws Exception {
        world.setBlock(BlockVector3.ZERO,block("buildertest:red_panel"));
        assertTrue(mask("#cc[red]").test(BlockVector3.ZERO));
        assertFalse(mask("#cc[blue]").test(BlockVector3.ZERO));
        assertEquals("buildertest:blue_panel",pattern("#cc[blue]").applyBlock(BlockVector3.ZERO).getBlockType().id());
        world.setBlock(BlockVector3.ZERO,block("buildertest:limestone_stairs"));
        assertTrue(mask("#type[limestone]").test(BlockVector3.ZERO));
        assertEquals("otherbuilder:marble_stairs",pattern("#type[marble]").applyBlock(BlockVector3.ZERO).getBlockType().id());
    }

    @Test void spatialMasksRespectAxisDirectionAndDistance() throws Exception {
        world.setBlock(BlockVector3.ZERO,block("gold_block"));
        assertTrue(mask("#above[gold_block][3]").test(BlockVector3.at(0,3,0)));
        assertFalse(mask("#above[gold_block][3]").test(BlockVector3.at(0,-1,0)));
        assertTrue(mask("#below[gold_block][3]").test(BlockVector3.at(0,-3,0)));
        assertTrue(mask("#prox[gold_block][3]").test(BlockVector3.at(0,2,0)));
        assertFalse(mask("#prox[gold_block][3]").test(BlockVector3.at(2,0,0)));
        assertTrue(mask("#prox3D[gold_block][3]").test(BlockVector3.at(2,0,0)));
        assertFalse(mask("#prox3D[gold_block][3]").test(BlockVector3.at(3,3,0)));
        assertTrue(mask("y[-1][3]").test(BlockVector3.at(0,3,0)));
        assertFalse(mask("y[-1][3]").test(BlockVector3.at(0,4,0)));
    }

    @ParameterizedTest @ValueSource(strings={"turbulence","electric","crack","cell","voronoi","fractal"})
    void noiseMasksParseAndGiveRepeatableResults(String kind) throws Exception {
        var mask=mask("#"+kind+(kind.equals("voronoi")?"[2][70]":"[2][50][70]"));
        for(int i=-10;i<10;i++) { var p=BlockVector3.at(i,i%3,5); assertEquals(mask.test(p),mask.copy().test(p)); }
    }

    @ParameterizedTest @ValueSource(strings={"turbulence","electric","arcvor","fractal","cell"})
    void noisePatternsStayWithinTheirModdedPalette(String kind) throws Exception {
        var pattern=pattern("#"+kind+"[8][buildertest:red_panel,buildertest:blue_panel]");
        Set<String> allowed=Set.of("buildertest:red_panel","buildertest:blue_panel");
        for(int i=0;i<32;i++) assertTrue(allowed.contains(pattern.applyBlock(BlockVector3.at(i,0,2)).getBlockType().id()));
    }

    @ParameterizedTest @ValueSource(strings={"simplex","fractal","billowy","ridged","turbulence","electric","euclidean","natural","poly","manhattan"})
    void terrainIsSeededBoundedAndAcceptsModdedPatterns(String kind) throws Exception {
        var region=new CuboidRegion(BlockVector3.ZERO,BlockVector3.at(7,7,7));
        var material=block("buildertest:red_panel");
        BuilderOperations.terrain(world,region,material,kind,8,16,0,123,false,false,false,false);
        var repeated=new MemoryExtent();
        BuilderOperations.terrain(repeated,region,material,kind,8,16,0,123,false,false,false,false);
        assertEquals(world.blocks,repeated.blocks); assertFalse(world.blocks.isEmpty());
        assertTrue(world.blocks.keySet().stream().allMatch(region::contains));
        assertTrue(world.blocks.values().stream().allMatch(material::equals));
    }

    @Test void wfcLearnsAdjacencyWithModdedStatesAndIsRepeatable() {
        BaseBlock a=block("buildertest:red_panel"),b=block("buildertest:blue_panel");
        var solver=new OverlappingWfc(new BaseBlock[][]{{a,b},{b,a}},2,1,true,'y');
        var output=solver.solve(12,42); assertArrayEquals(output,solver.solve(12,42));
        for(int y=0;y<12;y++) for(int x=0;x<12;x++) {
            assertTrue(output[y][x].equals(a)||output[y][x].equals(b));
            if(x>0) assertNotEquals(output[y][x],output[y][x-1]);
            if(y>0) assertNotEquals(output[y][x],output[y-1][x]);
        }
    }

    @Test void wfcRejectsContradictionsAndOversizedRequests() {
        var solver=new OverlappingWfc(new BaseBlock[][]{{block("stone"),block("dirt")},{block("glass"),block("gold_block")}},2,1,false,'y');
        assertThrows(IllegalArgumentException.class,() -> solver.solve(6,1));
        assertThrows(IllegalArgumentException.class,() -> solver.solve(129,1));
        assertThrows(IllegalArgumentException.class,() -> new OverlappingWfc(new BaseBlock[][]{{block("stone")}},2,1,false,'y'));
    }

    @Test void wfcSymmetriesTransformModdedDirectionalStates() throws Exception {
        BaseBlock stair=WorldEdit.getInstance().getBlockFactory().parseFromInput(
            "otherbuilder:marble_stairs[facing=east]",context);
        var output=new OverlappingWfc(new BaseBlock[][]{{stair}},1,8,true,'y').solve(8,44);
        Set<Object> facings=new HashSet<>();
        for(BaseBlock[] row:output) for(BaseBlock block:row) {
            assertEquals(stair.getBlockType(),block.getBlockType());
            facings.add(block.getState(block.getBlockType().getProperty("facing")));
        }
        assertEquals(4,facings.size());
    }

    @Test void invalidArgumentsProduceInputErrors() {
        for(String input:List.of("#cc[chartreuse]","#prox3D[stone][10000]","y[5][1]","#fractal[NaN]"))
            assertThrows(InputParseException.class,() -> mask(input),input);
        assertThrows(InputParseException.class,() -> pattern("#cell[0][stone]"));
    }

    @Test void surfaceMasksGradientAndUtilityPatternsHaveObservableEffects() throws Exception {
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) world.setBlock(BlockVector3.at(x,0,z),block("stone"));
        assertTrue(mask("#arcangle[0][1][1]").test(BlockVector3.ZERO));
        assertFalse(mask("#arcangle[60][90][1]").test(BlockVector3.ZERO));
        assertTrue(mask("#ambient[4]").test(BlockVector3.ZERO));
        assertTrue(mask("#ambient").test(BlockVector3.ZERO));
        assertFalse(mask("#ambient[5]").test(BlockVector3.ZERO));
        assertFalse(mask("#ygradient[0][10]").test(BlockVector3.at(0,0,0)));
        assertTrue(mask("#ygradient[0][10]").test(BlockVector3.at(0,10,0)));
        world.blocks.clear(); world.setBlock(BlockVector3.at(1,0,0),block("buildertest:blue_panel"));
        assertEquals(block("buildertest:blue_panel"),pattern("#blend").applyBlock(BlockVector3.ZERO));
        world.setBlock(BlockVector3.ZERO,block("white_wool"));
        assertEquals(block("black_wool"),pattern("#invert").applyBlock(BlockVector3.ZERO));
        var plant=pattern("#dp[0]");
        var lower=plant.applyBlock(BlockVector3.ZERO);
        var upper=plant.applyBlock(BlockVector3.at(0,1,0));
        assertEquals("lower",lower.getState(lower.getBlockType().getProperty("half")));
        assertEquals("upper",upper.getState(upper.getBlockType().getProperty("half")));
    }

    @Test void extendedNoiseOptionsAndWeightedPalettesWork() throws Exception {
        assertEquals(Math.sqrt(0.18),BuilderNoise.cellularSample("euclidean","distance",0.2,0.2,0.5,1,0),1e-9);
        assertEquals(0.6,BuilderNoise.cellularSample("manhattan","distance",0.2,0.2,0.5,1,0),1e-9);
        for(String input:List.of("#arcnoise[12345][cubic][145]",
            "#arcnoise[12345][perlinfractal][120][fbm][hermite][1][0.03]",
            "#arcnoise[69420][cellular][60][cellvalue][Natural][3][0.06][100]")) {
            var noise=mask(input);
            assertEquals(noise.test(BlockVector3.ZERO),noise.copy().test(BlockVector3.ZERO));
        }
        var weighted=pattern("#cell[2][100%buildertest:red_panel,0%buildertest:blue_panel][70][5]");
        for(int x=0;x<32;x++) assertEquals("buildertest:red_panel",weighted.applyBlock(BlockVector3.at(x,0,x)).getBlockType().id());
    }

    @Test void commandsRegisterPermissionsAliasesAndFlagsWithoutDuplicates() {
        var manager=new org.enginehub.piston.impl.CommandManagerImpl();
        BuilderCommands.register(manager); BuilderCommands.register(manager);
        assertEquals(6,manager.getAllCommands().count());
        assertTrue(manager.containsCommand("/tr")); assertTrue(manager.containsCommand("/tg"));
        var player=(com.sk89q.worldedit.entity.Player)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{com.sk89q.worldedit.entity.Player.class},(p,m,a) -> switch(m.getName()) {
            case "hasPermission" -> true; case "getUniqueId" -> new UUID(0,1); case "print" -> null; default -> throw new UnsupportedOperationException(m.getName());
        });
        var store=org.enginehub.piston.inject.MapBackedValueStore.create();
        store.injectValue(org.enginehub.piston.inject.Key.of(com.sk89q.worldedit.extension.platform.Actor.class),s -> Optional.of(player));
        store.injectValue(org.enginehub.piston.inject.Key.of(com.sk89q.worldedit.entity.Player.class),s -> Optional.of(player));
        assertDoesNotThrow(() -> manager.parse(store,List.of("/colorreplace","red","blue","-b","-g")));
        assertDoesNotThrow(() -> manager.parse(store,List.of("/terragen","fractal","buildertest:red_panel","8","16","-s","-v")));
        assertEquals(1,manager.execute(store,List.of("/tgseed","42")));
    }

    private BaseBlock block(String id) { return Objects.requireNonNull(BlockTypes.get(id.contains(":")?id:"minecraft:"+id)).getDefaultState().toBaseBlock(); }
    private com.sk89q.worldedit.function.mask.Mask mask(String input) throws InputParseException { return WorldEdit.getInstance().getMaskFactory().parseFromInput(input,context); }
    private com.sk89q.worldedit.function.pattern.Pattern pattern(String input) throws InputParseException { return WorldEdit.getInstance().getPatternFactory().parseFromInput(input,context); }

    static final class MemoryExtent extends NullExtent {
        final Map<BlockVector3,BaseBlock> blocks=new HashMap<>();
        @Override public BlockVector3 getMinimumPoint() { return BlockVector3.at(-100,-64,-100); }
        @Override public BlockVector3 getMaximumPoint() { return BlockVector3.at(100,128,100); }
        @Override public BlockState getBlock(BlockVector3 p) { return getFullBlock(p).toImmutableState(); }
        @Override public BlockState getBlock(int x,int y,int z) { return getBlock(BlockVector3.at(x,y,z)); }
        @Override public BaseBlock getFullBlock(BlockVector3 p) { return blocks.getOrDefault(p,BlockTypes.AIR.getDefaultState().toBaseBlock()); }
        @Override public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 p,B block) { return !Objects.equals(blocks.put(p.toImmutable(),block.toBaseBlock()),block.toBaseBlock()); }
    }
}
