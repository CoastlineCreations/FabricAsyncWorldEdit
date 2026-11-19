package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.fabric.FabricPlayer;
import com.sk89q.worldedit.fabric.internal.FabricPendingApplies;
import com.sk89q.worldedit.regions.Region;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class ReplacementMenus {
    private ReplacementMenus() { }

    static void open(FabricPlayer actor, LocalSession session, Region region, boolean colors, Set<Character> flags) {
        var player = actor.getServerPlayer();
        var server = player.level().getServer();
        Region expected = region.clone();
        Set<Character> options = Set.copyOf(flags);
        server.execute(() -> {
            if (player.hasDisconnected() || !player.isAlive()) return;
            String command = colors ? "colorreplace" : "typereplace";
            if (!actor.hasPermission("arceon.tool." + command)) {
                player.sendSystemMessage(Component.literal("You no longer have permission to use this tool."));
                return;
            }
            var choices = colors ? ReplacementChoices.colors() : ReplacementChoices.materials();
            if (choices.isEmpty()) {
                player.sendSystemMessage(Component.literal("No registered replacement families are available."));
                return;
            }
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new ReplacementMenu(id, inventory,
                colors, choices, request -> {
                    try {
                        Region current = session.getSelection(actor.getWorldForEditing());
                        if (!sameSelection(expected, current)) {
                            player.sendSystemMessage(Component.literal("Selection changed. Reopen //" + (colors ? "cr" : "tr") + " to try again."));
                            return;
                        }
                    } catch (com.sk89q.worldedit.IncompleteRegionException e) {
                        player.sendSystemMessage(Component.literal("Selection is incomplete. Select a region and reopen the menu."));
                        return;
                    }
                    if (player.hasDisconnected() || !actor.hasPermission("arceon.tool." + command)) {
                        player.sendSystemMessage(Component.literal("Replacement cancelled: permission or connection changed."));
                        return;
                    }
                    String arguments = options.stream().sorted().map(flag -> " -" + flag).collect(Collectors.joining());
                    FabricPendingApplies.drainAll();
                    player.sendSystemMessage(Component.literal("Replacement queued: " + request.from() + " -> " + request.to()));
                    // Normal FAWE command dispatch rechecks permissions and retains
                    // its queue, edit limits, masks, error handling and undo history.
                    WorldEdit.getInstance().getEventBus().post(new CommandEvent(actor,
                        "//" + command + " " + request.from() + " " + request.to() + arguments));
                }, () -> {
                    if (!player.hasDisconnected()) player.sendSystemMessage(Component.literal("Replacement cancelled."));
                }),
                Component.literal(colors ? "Color Replace: From > To" : "Type Replace: From > To")));
        });
    }

    static boolean sameSelection(Region expected, Region current) {
        return expected.getClass() == current.getClass()
            && Objects.equals(expected.getWorld(), current.getWorld())
            && expected.getMinimumPoint().equals(current.getMinimumPoint())
            && expected.getMaximumPoint().equals(current.getMaximumPoint());
    }
}
