package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.command.util.PermissionCondition;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.enginehub.piston.CommandManager;
import org.enginehub.piston.CommandParameters;
import org.enginehub.piston.inject.Key;
import org.enginehub.piston.part.CommandArgument;
import org.enginehub.piston.part.NoArgCommandFlag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/** Commands run in FAWE's queue with its injected EditSession/history lifecycle. */
public final class BuilderCommands {
    private static final Map<UUID,Long> SEEDS = new ConcurrentHashMap<>();
    private BuilderCommands() { }

    public static void register(CommandManager manager) {
        command(manager,"colorreplace",List.of("/cr"),"Recolor registered block families", "bg");
        command(manager,"typereplace",List.of("/tr"),"Replace materials while retaining block shapes", "fbd");
        command(manager,"wax",List.of(),"Wax copper and registered modded counterparts", "");
        command(manager,"terragen",List.of("/tg"),"Generate seeded terrain inside the selection", "svern");
        command(manager,"tgseed",List.of(),"Set the seed used by terragen -s", "");
        command(manager,"wfc",List.of(),"Generate a texture from a planar selection", "s");
    }

    public static void forget(UUID id) { SEEDS.remove(id); }

    private static void command(CommandManager manager,String name,List<String> aliases,String description,String flags) {
        if(manager.containsCommand("/"+name)) return;
        CommandArgument args=CommandArgument.builder(TranslatableComponent.of("arguments"),TextComponent.of(description))
            .variable(true).defaultsTo(List.of("")).build();
        Map<Character,NoArgCommandFlag> switches=new LinkedHashMap<>();
        var builder=manager.newCommand("/"+name).description(TextComponent.of(description))
            .footer(TextComponent.of(usage(name)))
            .aliases(aliases.stream().filter(a -> !manager.containsCommand(a)).toList())
            .condition(new PermissionCondition(Set.of("arceon.tool."+(name.equals("tgseed")?"terragen":name)),true));
        for(char flag:flags.toCharArray()) {
            NoArgCommandFlag part=NoArgCommandFlag.builder(flag,flagHelp(name,flag)).build();
            switches.put(flag,part); builder.addPart(part);
        }
        builder.addPart(args).action(parameters -> {
            List<String> values=parameters.valueOf(args).asStrings().stream().filter(s -> !s.isBlank()).toList();
            Set<Character> enabled=new HashSet<>(); switches.forEach((key,part) -> {if(parameters.has(part)) enabled.add(key);});
            try { return run(name,values.toArray(String[]::new),enabled,parameters); }
            catch(IllegalArgumentException e) { throw BuilderParsers.error(e.getMessage()); }
        });
        manager.register(builder.build());
    }

    private static int run(String name,String[] a,Set<Character> flags,CommandParameters params) throws Exception {
        Player player=params.injectedValue(Key.of(Player.class)).orElseThrow(() -> new IllegalArgumentException("This command requires a player"));
        if(name.equals("tgseed")) {
            BuilderParsers.count(a,1,1); long seed=Long.parseLong(a[0]); SEEDS.put(player.getUniqueId(),seed);
            player.print(TextComponent.of("Terrain seed: "+seed+". Use //terragen ... -s.")); return 1;
        }
        LocalSession local=params.injectedValue(Key.of(LocalSession.class)).orElseThrow();
        Region region=local.getSelection(player.getWorldForEditing());
        if(!name.equals("terragen")&&!(region instanceof CuboidRegion)) throw new IllegalArgumentException("This tool requires a cuboid selection");
        if(a.length==0&&(name.equals("colorreplace")||name.equals("typereplace"))) {
            if(!(player instanceof com.sk89q.worldedit.fabric.FabricPlayer actor)) throw new IllegalArgumentException("Inventory menus require a Fabric player; provide both replacement arguments instead");
            ReplacementMenus.open(actor,local,region,name.equals("colorreplace"),flags);
            return 1;
        }
        EditSession edit=params.injectedValue(Key.of(EditSession.class)).orElseThrow();
        ParserContext ctx=new ParserContext(); ctx.setActor(player); ctx.setSession(local); ctx.setWorld(player.getWorldForEditing()); ctx.setExtent(edit); ctx.setRestricted(true);
        BlockFamilies families=BlockFamilies.live();
        int changed;
        switch(name) {
            case "colorreplace": {
                BuilderParsers.count(a,2,2); String from=BlockFamilies.colorName(a[0]),to=BlockFamilies.colorName(a[1]);
                changed=BuilderOperations.replace(edit,region,checked(ctx,block -> {
                    String id=block.getBlockType().getResource();
                    if(!from.equals(BlockFamilies.color(block.toImmutableState()))||flags.contains('b')&&id.contains("banner")||flags.contains('g')&&id.contains("glazed_terracotta")) return block;
                    return families.recolor(block,to);
                })); break;
            }
            case "typereplace": {
                BuilderParsers.count(a,2,2);
                changed=BuilderOperations.replace(edit,region,checked(ctx,block -> {
                    String shape=BlockFamilies.shape(block.getBlockType().getResource());
                    if(flags.contains('f')&&Set.of("_wall","_fence","_fence_gate").contains(shape)) return block;
                    if(flags.contains('b')&&Set.of("","_block","_planks","_bricks","_tiles").contains(shape)) return block;
                    if(flags.contains('d')&&shape.equals("_slab")&&(block.getBlockType().getResource().contains("smooth")||block.getStates().values().contains("double"))) return block;
                    return families.retype(block,a[0],a[1]);
                })); break;
            }
            case "wax": {
                BuilderParsers.count(a,0,0); changed=BuilderOperations.replace(edit,region,checked(ctx,families::wax)); break;
            }
            case "terragen": {
                BuilderParsers.count(a,1,5); String kind=BuilderNoise.type(a[0]);
                Pattern pattern=WorldEdit.getInstance().getPatternFactory().parseFromInput(a.length>1?a[1]:"stone",ctx);
                int height=a.length>2?BuilderParsers.integer(a[2],1,region.getHeight()):region.getHeight();
                double zoom=a.length>3?BuilderParsers.number(a[3],0.001,1000000):32;
                double wave=a.length>4?BuilderParsers.number(a[4],-1000,1000):0;
                long seed=flags.contains('s')?SEEDS.computeIfAbsent(player.getUniqueId(),id -> ThreadLocalRandom.current().nextLong()):ThreadLocalRandom.current().nextLong();
                SEEDS.put(player.getUniqueId(),seed);
                changed=BuilderOperations.terrain(edit,region,pattern,kind,height,zoom,wave,seed,flags.contains('v'),flags.contains('e'),flags.contains('n'),flags.contains('r'));
                player.print(TextComponent.of("Terrain seed: "+seed)); break;
            }
            case "wfc": {
                BuilderParsers.count(a,1,3); int size=BuilderParsers.integer(a[0],1,128);
                int symmetry=a.length>1?BuilderParsers.integer(a[1],1,8):1;
                int sampleSize=a.length>2?BuilderParsers.integer(a[2],1,8):Math.min(3,Math.min(region.getWidth(),region.getLength()));
                char axis=region.getHeight()==1?'y':region.getLength()==1?'z':region.getWidth()==1?'x':'?';
                if(axis=='?') throw new IllegalArgumentException("WFC requires a flat cuboid sample (one dimension must be 1)");
                int width=axis=='x'?region.getHeight():region.getWidth(),height=axis=='y'?region.getLength():axis=='x'?region.getLength():region.getHeight();
                if(a.length<3) sampleSize=Math.min(3,Math.min(width,height));
                if((long)width*height>4096) throw new IllegalArgumentException("WFC source is limited to 4096 blocks");
                var min=region.getMinimumPoint();
                // Validate output bounds before the solver or any writes.
                var end=point(min,axis,size-1,size-1);
                if(end.y()>edit.getMaximumPoint().y()||min.y()<edit.getMinimumPoint().y()
                    ||Math.abs((long)min.x())>29999984||Math.abs((long)min.z())>29999984
                    ||Math.abs((long)end.x())>29999984||Math.abs((long)end.z())>29999984) throw new IllegalArgumentException("WFC output exceeds world bounds");
                BaseBlock[][] source=new BaseBlock[height][width];
                for(int y=0;y<height;y++) for(int x=0;x<width;x++) source[y][x]=edit.getFullBlock(point(min,axis,x,y));
                BaseBlock[][] output=new OverlappingWfc(source,sampleSize,symmetry,flags.contains('s'),axis).solve(size,ThreadLocalRandom.current().nextLong());
                for(BaseBlock[] row:output) for(BaseBlock block:row) validateTarget(ctx,block);
                changed=0;
                for(int y=0;y<size;y++) for(int x=0;x<size;x++) if(edit.setBlock(point(min,axis,x,y),output[y][x])) changed++;
                break;
            }
            default: throw new IllegalArgumentException("Unknown builder tool: "+name);
        }
        player.print(TextComponent.of("Builder tool: "+changed+" blocks changed.")); return changed;
    }

    private static UnaryOperator<BaseBlock> checked(ParserContext ctx,UnaryOperator<BaseBlock> mapper) {
        return source -> { BaseBlock result=mapper.apply(source); if(!result.equals(source)) validateTarget(ctx,result); return result; };
    }

    static void validateTarget(ParserContext ctx,BaseBlock block) {
        if(!ctx.isRestricted()||ctx.getActor()==null) return;
        String id=block.getBlockType().id(); var actor=ctx.getActor();
        if(!actor.hasPermission("worldedit.anyblock")&&WorldEdit.getInstance().getConfiguration().disallowedBlocks.contains(id)
            ||!actor.getLimit().isUnlimited()&&actor.getLimit().DISALLOWED_BLOCKS.contains(id)) throw new IllegalArgumentException("Disallowed block: "+id);
    }

    public static BlockVector3 point(BlockVector3 min,char normal,int x,int y) {
        return switch(normal) {case 'x' -> min.add(0,x,y);case 'z' -> min.add(x,y,0);default -> min.add(x,0,y);};
    }

    private static String usage(String name) {
        return switch(name) {
            case "colorreplace" -> "//cr opens the two-click color menu. Or //colorreplace <from-color> <to-color> [-bg]";
            case "typereplace" -> "//tr opens the two-click material menu. Or //typereplace <from-material> <to-material> [-fbd]. Example: //tr oak cobble";
            case "terragen" -> "//terragen <noise> [pattern=stone] [height=selection] [zoom=32] [wave=0] [-svern]";
            case "tgseed" -> "//tgseed <seed>. Follow with //terragen ... -s";
            case "wfc" -> "//wfc <size=1..128> [symmetry=1..8] [sample=1..8] [-s]. Select a flat sample first; output starts at its minimum corner.";
            default -> "//wax. Requires a cuboid selection.";
        };
    }

    private static String flagHelp(String name,char flag) {
        return switch(flag) {
            case 'b' -> name.equals("colorreplace")?"Skip banners":"Skip full blocks";
            case 'g' -> "Skip glazed terracotta";
            case 'f' -> "Skip fences, fence gates and walls";
            case 'd' -> "Skip double and smooth slabs";
            case 's' -> name.equals("wfc")?"Treat source sample as seamless":"Reuse the previous seed (or //tgseed)";
            case 'v' -> "Fade terrain toward the selection edges";
            case 'e' -> "Build on top of existing terrain";
            case 'r' -> "Maximize cellular feature jitter";
            case 'n' -> "Use eight noise octaves";
            default -> throw new IllegalArgumentException("Unknown flag: "+flag);
        };
    }
}
