package com.sk89q.worldedit.fabric.tools;

import com.fastasyncworldedit.core.extension.factory.parser.RichParser;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.world.item.DyeColor;

import java.util.*;
import java.util.function.Predicate;

public final class BuilderParsers {
    private static final int[][] NEIGHBORS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private BuilderParsers() { }

    public static void register(WorldEdit we) {
        mask(we, "color", "#cc", "#color");
        mask(we, "type", "#type", "#tr");
        mask(we, "y", "y", "#y");
        mask(we, "above", "#above"); mask(we, "below", "#below");
        mask(we, "prox", "#prox"); mask(we, "prox3d", "#prox3D", "#prox3d");
        mask(we, "angle", "#arcangle"); mask(we, "ambient", "#ambient");
        mask(we, "gradient", "#ygradient"); mask(we, "noise", "#arcnoise", "#noise");
        for (String name : List.of("turbulence", "electric", "crack", "cell", "voronoi", "fractal")) {
            mask(we, name, "#" + name, "#" + shortName(name));
        }
        pattern(we, "color", "#cc"); // FAWE's existing #color accepts RGB values.
        pattern(we, "type", "#type", "#tr");
        pattern(we, "invert", "#invert"); pattern(we, "blend", "#blend"); pattern(we, "plant", "#dp");
        pattern(we, "voronoi", "#arcvor");
        for (String name : List.of("turbulence", "electric", "fractal", "cell")) {
            pattern(we, name, "#" + name, "#" + shortName(name));
        }
    }

    private static String shortName(String name) {
        return switch (name) { case "turbulence" -> "turb"; case "electric" -> "elec"; case "fractal" -> "frac"; case "voronoi" -> "vor"; default -> name; };
    }

    private static void mask(WorldEdit we, String kind, String... aliases) {
        String[] names = Arrays.stream(aliases).distinct().filter(a -> !we.getMaskFactory().containsAlias(a)).toArray(String[]::new);
        if (names.length > 0) we.getMaskFactory().register(new RichParser<Mask>(we, names) {
            @Override public Mask parseFromInput(String input, ParserContext context) throws InputParseException {
                return getMatchedAliases().contains(input) ? parseFromInput(new String[0],context) : super.parseFromInput(input,context);
            }
            @Override protected Mask parseFromInput(String[] args, ParserContext context) throws InputParseException {
                try { return parseMask(we, kind, args, context); }
                catch (IllegalArgumentException e) { throw error(e.getMessage()); }
            }
        });
    }

    private static void pattern(WorldEdit we, String kind, String... aliases) {
        String[] names = Arrays.stream(aliases).distinct().filter(a -> !we.getPatternFactory().containsAlias(a)).toArray(String[]::new);
        if (names.length > 0) we.getPatternFactory().register(new RichParser<Pattern>(we, names) {
            @Override public Pattern parseFromInput(String input, ParserContext context) throws InputParseException {
                return getMatchedAliases().contains(input) ? parseFromInput(new String[0],context) : super.parseFromInput(input,context);
            }
            @Override protected Pattern parseFromInput(String[] args, ParserContext context) throws InputParseException {
                try {
                    Pattern pattern = parsePattern(we, kind, args, context);
                    return p -> { BaseBlock block = pattern.applyBlock(p); BuilderCommands.validateTarget(context, block); return block; };
                }
                catch (IllegalArgumentException e) { throw error(e.getMessage()); }
            }
        });
    }

    private static Mask parseMask(WorldEdit we, String kind, String[] a, ParserContext ctx) throws InputParseException {
        Extent extent = ctx.requireExtent();
        switch (kind) {
            case "color": {
                count(a, 1, 1); String color = BlockFamilies.colorName(a[0]);
                return functional(p -> color.equals(BlockFamilies.color(extent.getBlock(p))));
            }
            case "type": {
                count(a, 1, 1); return functional(p -> BlockFamilies.isType(extent.getBlock(p).getBlockType(), a[0]));
            }
            case "y": case "gradient": {
                count(a, 2, 2); int low = integer(a[0], -30000000, 30000000), high = integer(a[1], low, 30000000);
                return functional(p -> kind.equals("y") ? p.y() >= low && p.y() <= high
                    : p.y() >= high || p.y() > low && BuilderNoise.unit(BuilderNoise.hash(p.x(), p.y(), p.z(), 0)) < (p.y() - (double)low) / (high - (double)low));
            }
            case "above": case "below": case "prox": case "prox3d": {
                count(a, 1, 2); Mask target = we.getMaskFactory().parseFromInput(a[0], ctx);
                int radius = a.length == 2 ? integer(a[1], 1, kind.equals("prox3d") ? 16 : 256) : 1;
                return functional(p -> near(target, extent, p, radius, kind));
            }
            case "ambient": {
                count(a, 0, 1); int min = a.length == 0 ? 2 : integer(a[0], 0, 6);
                return functional(p -> {
                    if (extent.getBlock(p).isAir()) return false;
                    int neighbors = 0;
                    for (int[] d : NEIGHBORS) if (!extent.getBlock(p.add(d[0],d[1],d[2])).isAir()) neighbors++;
                    return neighbors >= min && neighbors < 6;
                });
            }
            case "angle": {
                count(a, 2, 3); double low = number(a[0],0,90), high = number(a[1],low,90);
                int radius = a.length == 3 ? integer(a[2],1,2) : 1;
                return functional(p -> {
                    double angle=surfaceAngle(extent,p,radius);
                    return angle >= low && angle <= high;
                });
            }
            default: {
                if (kind.equals("voronoi")) {
                    count(a,0,2); double scale=(a.length>0?number(a[0],0.001,1000000):1)*16;
                    double jitter=a.length>1?percentage(a[1]):0.5;
                    return functional(p -> BuilderNoise.sample("crack",p.x()/scale,p.y()/scale,p.z()/scale,0,jitter,1)<0.15);
                }
                if (kind.equals("noise")) {
                    count(a, 3, 8); long seed=Long.parseLong(a[0]); String type=BuilderNoise.type(a[1]);
                    double threshold=number(a[2],-255,255); double frequency=a.length>=7?number(a[6],0.000001,100):0.03;
                    int octaves=a.length>=6?integer(a[5],1,8):4;
                    // Accept the documented FastNoise-style options explicitly.
                    if(a.length>=4 && !Set.of("fbm","billow","rigidmulti","cellvalue","distance","distance2").contains(a[3].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unknown noise mode: "+a[3]);
                    if(a.length>=5 && !Set.of("linear","hermite","quintic","natural","euclidean","manhattan").contains(a[4].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unknown interpolation/distance: "+a[4]);
                    if(a.length>=5 && Set.of("natural","euclidean","manhattan").contains(a[4].toLowerCase(Locale.ROOT)) && !a[3].equalsIgnoreCase("cellvalue")) type=a[4];
                    if(a.length>=4 && a[3].equalsIgnoreCase("billow")) type="billowy";
                    if(a.length>=4 && a[3].equalsIgnoreCase("rigidmulti")) type="ridged";
                    if(a.length>=4 && a[3].equalsIgnoreCase("fbm") && type.equals("simplex")) type="fractal";
                    double jitter=a.length==8?number(a[7],0,100)/100:0.7;
                    String field=type;
                    String mode=a.length>=4?a[3].toLowerCase(Locale.ROOT):"fbm";
                    String curve=a.length>=5?a[4].toLowerCase(Locale.ROOT):"linear";
                    return functional(p -> {
                        double value;
                        if(Set.of("cellvalue","distance","distance2").contains(mode)) {
                            String metric=Set.of("manhattan","natural").contains(curve)?curve:"euclidean";
                            value=BuilderNoise.cellularSample(metric,mode,p.x()*frequency,p.y()*frequency,p.z()*frequency,seed,jitter);
                        } else value=BuilderNoise.sample(field,p.x()*frequency,p.y()*frequency,p.z()*frequency,seed,jitter,octaves);
                        if(curve.equals("hermite")) value=value*value*(3-2*value);
                        if(curve.equals("quintic")) value=value*value*value*(value*(value*6-15)+10);
                        return value*510-255 < threshold;
                    });
                }
                count(a,0,3); double scale=(a.length>0?number(a[0],0.001,1000000):1)*16;
                double coverage=a.length>1?percentage(a[1]):0.5, jitter=a.length>2?percentage(a[2]):0.7;
                return functional(p -> BuilderNoise.sample(kind,p.x()/scale,p.y()/scale,p.z()/scale,0,jitter,4) < coverage);
            }
        }
    }

    private static Pattern parsePattern(WorldEdit we, String kind, String[] a, ParserContext ctx) throws InputParseException {
        Extent extent=ctx.requireExtent();
        BlockFamilies families=BlockFamilies.live();
        switch(kind) {
            case "color": {
                count(a,1,1); String color=BlockFamilies.colorName(a[0]);
                return p -> families.recolor(extent.getFullBlock(p),color);
            }
            case "type": {
                count(a,1,1); return p -> families.retype(extent.getFullBlock(p),null,a[0]);
            }
            case "invert": {
                count(a,0,0); return p -> {
                    BaseBlock original=extent.getFullBlock(p); String color=BlockFamilies.color(original.toImmutableState());
                    return color==null?original:families.recolor(original,inverseColor(color));
                };
            }
            case "blend": {
                count(a,0,1); Mask target=a.length==1?we.getMaskFactory().parseFromInput(a[0],ctx):functional(p -> !extent.getBlock(p).isAir());
                return p -> {
                    List<BaseBlock> choices=new ArrayList<>();
                    for(int[] d:NEIGHBORS) { var next=p.add(d[0],d[1],d[2]); if(target.test(next)) choices.add(extent.getFullBlock(next)); }
                    return choices.isEmpty()?extent.getFullBlock(p):choices.get(Math.floorMod(BuilderNoise.hash(p.x(),p.y(),p.z(),0),choices.size()));
                };
            }
            case "plant": {
                count(a,1,1); int data=integer(a[0],0,5);
                String[] names={"sunflower","lilac","tall_grass","large_fern","rose_bush","peony"};
                var type=Objects.requireNonNull(BlockTypes.get("minecraft:"+names[data]));
                var property=type.getProperty("half");
                int bottom=ctx.getSession()!=null?ctx.getSelection().getMinimumY():extent.getMinimumPoint().y();
                return p -> plant(type,property,Math.floorMod(p.y()-bottom,2)==0?"lower":"upper");
            }
            default: {
                count(a,2,kind.equals("cell")?4:3); double scale=number(a[0],0.001,1000000);
                Pattern palette=we.getPatternFactory().parseFromInput(a[1],ctx);
                double jitter=kind.equals("cell")&&a.length>2?percentage(a[2]):0.7;
                long seed=a.length>(kind.equals("cell")?3:2)?Long.parseLong(a[a.length-1]):0;
                return palette instanceof RandomPattern random
                    ? new RandomPattern((x,y,z) -> Math.min(Math.nextDown(1.0),BuilderNoise.sample(kind,x/scale,y/scale,z/scale,seed,jitter,4)),random)
                    : palette;
            }
        }
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static BaseBlock plant(com.sk89q.worldedit.world.block.BlockType type, com.sk89q.worldedit.registry.state.Property property, String half) {
        return type.getDefaultState().with(property,half).toBaseBlock();
    }

    private static String inverseColor(String name) {
        int rgb=DyeColor.valueOf(name.toUpperCase(Locale.ROOT)).getTextureDiffuseColor();
        int r=255-(rgb>>16&255),g=255-(rgb>>8&255),b=255-(rgb&255),best=Integer.MAX_VALUE;
        String result=name;
        for(String candidate:BlockFamilies.COLORS) {
            int c=DyeColor.valueOf(candidate.toUpperCase(Locale.ROOT)).getTextureDiffuseColor();
            int d=(r-(c>>16&255))*(r-(c>>16&255))+(g-(c>>8&255))*(g-(c>>8&255))+(b-(c&255))*(b-(c&255));
            if(d<best) { best=d; result=candidate; }
        }
        return result;
    }

    private static boolean near(Mask target,Extent extent,BlockVector3 p,int radius,String mode) {
        int min=extent.getMinimumPoint().y(),max=extent.getMaximumPoint().y();
        for(int x=mode.equals("prox3d")?-radius:0;x<=(mode.equals("prox3d")?radius:0);x++)
            for(int z=mode.equals("prox3d")?-radius:0;z<=(mode.equals("prox3d")?radius:0);z++)
                for(int y=-radius;y<=radius;y++) {
                    if(mode.equals("above")&&y>=0 || mode.equals("below")&&y<=0) continue;
                    if(mode.equals("prox3d")&&x*x+y*y+z*z>radius*radius) continue;
                    if(p.y()+y>=min&&p.y()+y<=max&&target.test(p.add(x,y,z))) return true;
                }
        return false;
    }

    /** Fit a plane to occupied samples. Its normal also works for thin surfaces,
     * where opposite air-face gradients would cancel out. */
    private static double surfaceAngle(Extent extent,BlockVector3 p,int radius) {
        if(extent.getBlock(p).isAir()) return -1;
        double[] sum=new double[3]; double[][] covariance=new double[3][3];
        int count=0; boolean exposed=false;
        for(int x=-radius;x<=radius;x++) for(int y=-radius;y<=radius;y++) for(int z=-radius;z<=radius;z++) {
            if(extent.getBlock(p.add(x,y,z)).isAir()) { exposed=true; continue; }
            count++; sum[0]+=x; sum[1]+=y; sum[2]+=z;
            covariance[0][0]+=x*x; covariance[1][1]+=y*y; covariance[2][2]+=z*z;
            covariance[0][1]+=x*y; covariance[0][2]+=x*z; covariance[1][2]+=y*z;
        }
        if(!exposed||count<3) return -1;
        covariance[1][0]=covariance[0][1]; covariance[2][0]=covariance[0][2]; covariance[2][1]=covariance[1][2];
        for(int i=0;i<3;i++) for(int j=0;j<3;j++) covariance[i][j]-=sum[i]*sum[j]/count;
        double[][] basis={{1,0,0},{0,1,0},{0,0,1}};
        for(int iteration=0;iteration<12;iteration++) {
            int a=0,b=1;
            for(int i=0;i<3;i++) for(int j=i+1;j<3;j++)
                if(Math.abs(covariance[i][j])>Math.abs(covariance[a][b])) { a=i; b=j; }
            if(Math.abs(covariance[a][b])<1e-9) break;
            double angle=0.5*Math.atan2(2*covariance[a][b],covariance[b][b]-covariance[a][a]);
            double c=Math.cos(angle),s=Math.sin(angle);
            double aa=covariance[a][a],bb=covariance[b][b],ab=covariance[a][b];
            covariance[a][a]=c*c*aa-2*s*c*ab+s*s*bb;
            covariance[b][b]=s*s*aa+2*s*c*ab+c*c*bb;
            covariance[a][b]=covariance[b][a]=0;
            for(int k=0;k<3;k++) {
                if(k!=a&&k!=b) {
                    double ka=covariance[k][a],kb=covariance[k][b];
                    covariance[k][a]=covariance[a][k]=c*ka-s*kb;
                    covariance[k][b]=covariance[b][k]=s*ka+c*kb;
                }
                double va=basis[k][a],vb=basis[k][b]; basis[k][a]=c*va-s*vb; basis[k][b]=s*va+c*vb;
            }
        }
        int normal=0; for(int i=1;i<3;i++) if(covariance[i][i]<covariance[normal][normal]) normal=i;
        return Math.toDegrees(Math.acos(Math.clamp(Math.abs(basis[1][normal]),0,1)));
    }

    private record PredicateMask(Predicate<BlockVector3> predicate) implements Mask {
        @Override public boolean test(BlockVector3 p) { return predicate.test(p); }
        @Override public Mask copy() { return new PredicateMask(predicate); }
    }
    private static Mask functional(Predicate<BlockVector3> test) { return new PredicateMask(test); }
    static InputParseException error(String text) { return new InputParseException(TextComponent.of(text)); }
    static void count(String[] args,int min,int max) {
        if(args.length<min||args.length>max) throw new IllegalArgumentException("Expected "+min+".."+max+" arguments; got "+args.length);
    }
    static double number(String s,double min,double max) {
        double v=Double.parseDouble(s); if(!Double.isFinite(v)||v<min||v>max) throw new IllegalArgumentException("Value must be between "+min+" and "+max+": "+s); return v;
    }
    static int integer(String s,int min,int max) { int v=Integer.parseInt(s); if(v<min||v>max) throw new IllegalArgumentException("Integer must be between "+min+" and "+max+": "+s); return v; }
    private static double percentage(String s) { return number(s.replace("%",""),0,100)/100; }
}
