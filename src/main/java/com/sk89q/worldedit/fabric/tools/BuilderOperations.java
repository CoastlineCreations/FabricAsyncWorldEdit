package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.registry.state.Property;

import java.util.function.UnaryOperator;

public final class BuilderOperations {
    private BuilderOperations() { }

    public static int replace(Extent extent, Region region, UnaryOperator<BaseBlock> transform) throws WorldEditException {
        int changed=0;
        for(BlockVector3 p:region) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("Edit interrupted");
            BaseBlock original=extent.getFullBlock(p),result=transform.apply(original);
            if(!result.equals(original)&&extent.setBlock(p,result)) changed++;
        }
        return changed;
    }

    /** Height field clipped to the actual region; optional caps use slabs/snow. */
    public static int terrain(Extent extent,Region region,Pattern pattern,String kind,int height,double zoom,double wave,
                              long seed,boolean vignette,boolean existing,boolean moreNoise,boolean randomCells) throws WorldEditException {
        BuilderNoise.type(kind);
        if(height<1||height>region.getHeight()) throw new IllegalArgumentException("Height must fit within the selection");
        if(!Double.isFinite(zoom)||zoom<=0||!Double.isFinite(wave)) throw new IllegalArgumentException("Zoom must be positive and wave must be finite");
        var min=region.getMinimumPoint(); var max=region.getMaximumPoint(); int changed=0;
        for(int z=min.z();z<=max.z();z++) for(int x=min.x();x<=max.x();x++) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("Edit interrupted");
            int base=min.y();
            if(existing) {
                for(int y=max.y();y>=min.y();y--) {
                    if(region.contains(x,y,z)&&!extent.getBlock(x,y,z).isAir()) { base=y+1; break; }
                }
            }
            double px=x/zoom,pz=z/zoom;
            if(wave!=0) { px+=Math.sin(pz)*wave; pz+=Math.cos(px)*wave; }
            double n=BuilderNoise.sample(kind,px,0,pz,seed,randomCells?1:0.7,moreNoise?8:4);
            if(vignette) {
                double dx=(x-(min.x()+max.x())/2.0)/Math.max(0.5,region.getWidth()/2.0);
                double dz=(z-(min.z()+max.z())/2.0)/Math.max(0.5,region.getLength()/2.0);
                n*=Math.pow(Math.max(0,1-Math.max(dx*dx,dz*dz)),2);
            }
            double thickness=n*height;
            int full=(int)Math.floor(thickness);
            for(int y=base;y<=Math.min(max.y(),base+full);y++) {
                var p=BlockVector3.at(x,y,z);
                if(!region.contains(p)||y<extent.getMinimumPoint().y()||y>extent.getMaximumPoint().y()) continue;
                double fraction=y==base+full?thickness-full:1;
                if(fraction<=1e-9) continue;
                BaseBlock block=pattern.applyBlock(p);
                BaseBlock detail=cap(block,fraction,y==base+full);
                if(!detail.equals(extent.getFullBlock(p))&&extent.setBlock(p,detail)) changed++;
            }
        }
        return changed;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static BaseBlock cap(BaseBlock block,double fraction,boolean top) {
        var type=block.getBlockType();
        Property slab=type.getPropertyMap().get("type");
        if(type.getResource().endsWith("_slab")&&slab!=null&&slab.getValues().contains("double"))
            return block.with(slab,top&&fraction<=0.5?"bottom":"double");
        Property layers=type.getPropertyMap().get("layers");
        if(layers!=null&&layers.getValues().contains(8)) return block.with(layers,top?Math.clamp((int)Math.ceil(fraction*8),1,8):8);
        return block;
    }
}
