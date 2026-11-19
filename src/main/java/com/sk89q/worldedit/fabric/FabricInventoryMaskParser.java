package com.sk89q.worldedit.fabric;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.internal.registry.SimpleInputParser;
import com.sk89q.worldedit.world.block.BlockState;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Route exact inventory references through block parsing, not regex probing. */
final class FabricInventoryMaskParser extends SimpleInputParser<Mask> {
    private static final List<String> ALIASES = Stream.concat(
        Stream.of("hand", "offhand", "h", "oh"),
        IntStream.rangeClosed(1, Inventory.INVENTORY_SIZE).mapToObj(slot -> "slot" + slot)
    ).toList();

    FabricInventoryMaskParser(WorldEdit worldEdit) {
        super(worldEdit);
    }

    @Override
    public List<String> getMatchedAliases() {
        return ALIASES;
    }

    @Override
    public Mask parseFromInput(String input, ParserContext context) throws InputParseException {
        int properties = input.indexOf('[');
        String reference = properties < 0 ? input : input.substring(0, properties);
        return ALIASES.contains(reference) ? parseFromSimpleInput(input, context) : null;
    }

    @Override
    public Mask parseFromSimpleInput(String input, ParserContext context) throws InputParseException {
        // The core's regex probe has a 5 ms timeout and can return an empty mask
        // before falling back to inventory lookup. Exact aliases skip that probe.
        ParserContext blocks = new ParserContext(context);
        blocks.setRestricted(false);
        blocks.setPreferringWildcard(true);
        var block = worldEdit.getBlockFactory().parseFromInput(input, blocks);
        // Expand fuzzy properties explicitly: adding their placeholder ordinal
        // directly to a BlockMask would lose partial-state matching.
        BlockState[] matches = block.getBlockType().getAllStates().stream()
            .filter(block::equalsFuzzy).toArray(BlockState[]::new);
        return new BlockMask(context.requireExtent()).add(matches);
    }
}
