package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.HoneycombItem;

import java.util.*;
import java.util.regex.Pattern;

/** Resolves actual registered counterparts, including modded block families. */
public final class BlockFamilies {
    public static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow",
        "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Pattern COLOR = Pattern.compile("(^|[_/])(light_blue|light_gray|white|orange|magenta|yellow|lime|pink|gray|cyan|purple|blue|brown|green|red|black)(?=[_/]|$)");
    private static final List<String> SHAPES = List.of("_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_pressure_plate",
        "_fence_gate", "_trapdoor", "_button", "_stairs", "_slab", "_wall", "_fence", "_door", "_sign", "_chest", "_pane", "_carpet", "_wool", "_bars",
        "_planks", "_bricks", "_tiles", "_block");
    private final Map<String, BlockType> blocks;
    private record Resolution(String original, List<String> names, String explicit) { }
    private final java.util.concurrent.ConcurrentMap<Resolution, Optional<BlockType>> resolutions = new java.util.concurrent.ConcurrentHashMap<>();

    public BlockFamilies(Collection<BlockType> types) {
        Map<String, BlockType> map = new TreeMap<>();
        for (BlockType type : types) map.put(type.id(), type);
        blocks = Map.copyOf(map);
    }

    public static BlockFamilies live() {
        BlockTypes.size(); // Populate the lazy FAWE block cache before taking a snapshot.
        return new BlockFamilies(BlockType.REGISTRY.values());
    }

    public static String colorName(String value) {
        String color = value.toLowerCase(Locale.ROOT).replace('-', '_').replace("grey", "gray");
        if (!COLORS.contains(color)) throw new IllegalArgumentException("Unknown dye color: " + value);
        return color;
    }

    public static String color(BlockState state) {
        var match = COLOR.matcher(state.getBlockType().getResource());
        if (match.find()) return match.group(2);
        for (Property<?> property : state.getBlockType().getProperties()) {
            if (property.getName().equals("color")) {
                String value = String.valueOf(state.getState(property));
                if (COLORS.contains(value)) return value;
            }
        }
        return null;
    }

    public static boolean isType(BlockType block, String family) {
        String[] key = split(family);
        if (key[0] != null && !key[0].equals(block.getNamespace())) return false;
        String name = block.getResource();
        String material = material(key[1]);
        if (name.equals(material) || name.startsWith(material + "_")) return true;
        String basename = name.substring(name.lastIndexOf('/') + 1);
        return !material.contains("/") && (basename.equals(material) || basename.startsWith(material + "_"));
    }

    public BaseBlock recolor(BaseBlock source, String target) {
        String to = colorName(target);
        String name = source.getBlockType().getResource();
        var match = COLOR.matcher(name);
        if (match.find()) {
            String changed = name.substring(0, match.start(2)) + to + name.substring(match.end(2));
            BlockType result = resolve(source.getBlockType().getNamespace(), List.of(changed), null);
            if (result != null) return transfer(source, result);
        }
        for (Property<?> property : source.getBlockType().getProperties()) {
            if (property.getName().equals("color") && property.getValues().contains(to)) {
                return with(source, property, to);
            }
        }
        return source;
    }

    public BaseBlock retype(BaseBlock source, String from, String target) {
        BlockType type = source.getBlockType();
        if (from != null && !isType(type, from)) return source;
        String[] to = split(target);
        String material = material(to[1]);
        List<String> candidates = new ArrayList<>();
        if (from != null) {
            String old = material(split(from)[1]);
            String changed = type.getResource().replaceFirst("(^|[_/])" + Pattern.quote(old) + "(?=[_/]|$)", "$1" + java.util.regex.Matcher.quoteReplacement(material));
            if (!changed.equals(type.getResource())) candidates.add(changed);
        }
        String shape = shape(type.getResource());
        boolean unknownRole=from!=null&&shape.isEmpty()&&type.getResource().startsWith(material(split(from)[1])+"_");
        if (unknownRole) {
            // A missing material-specific door/decoration must not become a full block.
        } else if (List.of("_planks", "_block", "_bricks", "_tiles", "").contains(shape)) {
            candidates.addAll(List.of(material, material + "_block", material + "_planks", material + "_bricks", material + "_tiles"));
        } else {
            candidates.add(material + shape);
            String stem = material.endsWith("s") ? material.substring(0, material.length() - 1) : material;
            candidates.add(stem + shape);
            if (shape.equals("_fence")) candidates.add(material + "_wall");
            if (shape.equals("_wall")) candidates.add(material + "_fence");
        }
        BlockType result = resolve(type.getNamespace(), candidates, to[0]);
        return result == null ? source : transfer(source, result);
    }

    private BlockType resolve(String originalNamespace, List<String> names, String explicitNamespace) {
        return resolutions.computeIfAbsent(new Resolution(originalNamespace,List.copyOf(names),explicitNamespace),
            key -> Optional.ofNullable(resolveUncached(key.original(),key.names(),key.explicit()))).orElse(null);
    }

    private BlockType resolveUncached(String originalNamespace, List<String> names, String explicitNamespace) {
        List<String> namespaces = explicitNamespace != null ? List.of(explicitNamespace)
            : originalNamespace.equals("minecraft") ? List.of("minecraft") : List.of(originalNamespace, "minecraft");
        for (String namespace : namespaces) {
            for (String name : names) {
                BlockType result = blocks.get(namespace + ":" + name);
                if (result != null) return result;
            }
        }
        if (explicitNamespace == null) {
            for (String name : names) {
                List<BlockType> matches = blocks.values().stream().filter(type -> type.getResource().equals(name)).toList();
                if (matches.size() == 1) return matches.getFirst();
            }
        }
        return null;
    }

    public BaseBlock wax(BaseBlock source) {
        var waxed = HoneycombItem.getWaxed(FabricAdapter.adapt(source.toImmutableState()));
        if (waxed.isPresent()) return transfer(source, FabricAdapter.adapt(waxed.get().getBlock()));
        if (source.getBlockType().getResource().startsWith("waxed_")) return source;
        // Mods can expose waxed counterparts without contributing to vanilla's map.
        BlockType counterpart = blocks.get(source.getBlockType().getNamespace() + ":waxed_" + source.getBlockType().getResource());
        return counterpart == null ? source : transfer(source, counterpart);
    }

    public static String shape(String name) {
        for (String shape : SHAPES) if (name.endsWith(shape)) return shape;
        return "";
    }

    public static BaseBlock transfer(BaseBlock source, BlockType target) {
        if (source.getBlockType().equals(target)) return source;
        BlockState state = target.getDefaultState();
        for (Property<?> old : source.getBlockType().getProperties()) {
            Property<?> next = target.getPropertyMap().get(old.getName());
            Object value = source.getState(old);
            if (next != null && next.getValues().contains(value)) state = with(state, next, value);
        }
        if (source.getNbt() != null && target.getMaterial().hasContainer()) {
            var nbt = NBTConverter.toNative(source.getNbt());
            var id = nbt.getString("id").map(Identifier::tryParse).orElse(null);
            var entityType = id == null ? null : FabricWorldEdit.getRegistry(Registries.BLOCK_ENTITY_TYPE).getValue(id);
            if (entityType != null && entityType.isValid(FabricAdapter.adapt(state))) return state.toBaseBlock(source.getNbtReference());
        }
        return state.toBaseBlock();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends com.sk89q.worldedit.world.block.BlockStateHolder<T>> T with(T state, Property property, Object value) {
        return (T) state.with(property, value);
    }

    private static String material(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) { case "cobble" -> "cobblestone"; case "wood" -> "oak"; default -> name.toLowerCase(Locale.ROOT); };
    }

    private static String[] split(String key) {
        String[] parts = key.toLowerCase(Locale.ROOT).split(":", 2);
        return parts.length == 2 ? parts : new String[]{null, parts[0]};
    }
}
