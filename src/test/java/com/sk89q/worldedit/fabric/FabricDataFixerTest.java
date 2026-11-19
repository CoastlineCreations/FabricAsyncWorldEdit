package com.sk89q.worldedit.fabric;

import com.google.gson.JsonParser;
import com.sk89q.worldedit.world.DataFixer;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricDataFixerTest {
    private static FabricDataFixer fixer;

    @BeforeAll
    static void createFixer() {
        SharedConstants.tryDetectVersion();
        fixer = new FabricDataFixer(SharedConstants.getCurrentVersion().dataVersion().version());
    }

    @Test
    void convertsLegacyBlockWithoutProperties() {
        assertEquals("minecraft:stone", fix("minecraft:stone"));
    }

    @Test
    void preservesLegacyBlockProperties() {
        assertEquals("minecraft:oak_log[axis=x]", fix("minecraft:oak_log[axis=x]"));
    }

    @Test
    void appliesBlockRenames() {
        assertEquals("minecraft:dirt_path", fix("minecraft:grass_path"));
    }

    @Test
    void convertsEveryBuiltInLegacyBlockState() throws Exception {
        try (var stream = getClass().getResourceAsStream("/com/sk89q/worldedit/world/registry/legacy.json")) {
            assertNotNull(stream);
            var blocks = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("blocks");
            assertTrue(blocks.size() > 0);
            for (var entry : blocks.entrySet()) {
                String state = entry.getValue().getAsString();
                String converted = assertDoesNotThrow(() -> fix(state), entry.getKey() + ": " + state);
                assertTrue(converted.startsWith("minecraft:"), converted);
            }
        }
    }

    private String fix(String blockState) {
        // 1.13.2 is the source version of FAWE's built-in legacy registry.
        return fixer.fixUp(DataFixer.FixTypes.BLOCK_STATE, blockState, 1631);
    }
}
