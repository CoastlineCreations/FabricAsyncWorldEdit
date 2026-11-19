package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class ReplacementChoices {
    record Choice(String key, String label, String namespace, Item icon) { }
    private record Representative(BlockType type, int rank) { }

    private ReplacementChoices() { }

    static List<Choice> colors() {
        return BlockFamilies.COLORS.stream().map(color -> new Choice(color, label(color), "minecraft",
            BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:" + color + "_wool")))).toList();
    }

    static List<Choice> materials() {
        BlockTypes.size();
        Map<String, Representative> families = new TreeMap<>();
        for (BlockType type : BlockType.REGISTRY.values()) {
            String resource = type.getResource();
            if (type.getMaterial().isAir() || resource.startsWith("__")) continue;
            String shape = BlockFamilies.shape(resource);
            String material = shape.isEmpty() ? resource : resource.substring(0, resource.length() - shape.length());
            if (material.isEmpty()) continue;
            String key = type.getNamespace() + ":" + material;
            int rank = switch (shape) {
                case "" -> 0;
                case "_block" -> 1;
                case "_planks" -> 2;
                case "_bricks", "_tiles" -> 3;
                default -> 4;
            };
            Representative current = families.get(key);
            if (current == null || rank < current.rank()
                || rank == current.rank() && type.id().compareTo(current.type().id()) < 0) {
                families.put(key, new Representative(type, rank));
            }
        }
        List<Choice> choices = new ArrayList<>();
        families.forEach((key, value) -> {
            Item icon = FabricAdapter.adapt(value.type()).asItem();
            if (icon == Items.AIR) icon = Items.BRICKS;
            choices.add(new Choice(key, label(key.substring(key.indexOf(':') + 1)), value.type().getNamespace(), icon));
        });
        choices.sort(Comparator.comparing((Choice choice) -> !choice.namespace().equals("minecraft"))
            .thenComparing(Choice::namespace).thenComparing(Choice::label).thenComparing(Choice::key));
        return List.copyOf(choices);
    }

    static String label(String key) {
        String[] words = key.replace('_', ' ').replace('/', ' ').split(" +");
        List<String> parts = new ArrayList<>();
        for (String word : words) {
            if (!word.isEmpty()) parts.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return String.join(" ", parts);
    }
}
