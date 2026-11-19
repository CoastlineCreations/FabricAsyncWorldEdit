package com.sk89q.worldedit.fabric.tools;

import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.world.block.BaseBlock;

import java.util.*;

/** Bounded, weighted overlapping WFC. Solves completely before any world writes. */
public final class OverlappingWfc {
    private static final int[] DX={1,-1,0,0},DY={0,0,1,-1};
    private final int n;
    private final List<List<BaseBlock>> tiles;
    private final int[] weights;
    private final BitSet[][] compatible;

    public OverlappingWfc(BaseBlock[][] sample,int tileSize,int symmetry,boolean seamless,char normalAxis) {
        if(sample.length==0||sample[0].length==0||tileSize<1||tileSize>8||symmetry<1||symmetry>8)
            throw new IllegalArgumentException("WFC needs a nonempty sample, sample size 1..8 and symmetry 1..8");
        n=tileSize; int h=sample.length,w=sample[0].length;
        for(BaseBlock[] row:sample) if(row.length!=w||Arrays.stream(row).anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid sample grid");
        if(w<n||h<n) throw new IllegalArgumentException("Sample selection must be at least "+n+" by "+n);
        if((long)w*h>4096) throw new IllegalArgumentException("WFC sample is limited to 4096 blocks");
        Map<List<BaseBlock>,Integer> counts=new LinkedHashMap<>();
        for(int y=0;y<(seamless?h:h-n+1);y++) for(int x=0;x<(seamless?w:w-n+1);x++) {
            BaseBlock[][] tile=new BaseBlock[n][n];
            for(int j=0;j<n;j++) for(int i=0;i<n;i++) tile[j][i]=sample[(y+j)%h][(x+i)%w];
            for(int variation=0;variation<symmetry;variation++) {
                List<BaseBlock> variant=variant(tile,variation,normalAxis);
                counts.merge(variant,1,Integer::sum);
                if(counts.size()>512) throw new IllegalArgumentException("Sample has more than 512 distinct tiles; reduce sample size or symmetry");
            }
        }
        tiles=List.copyOf(counts.keySet()); weights=counts.values().stream().mapToInt(Integer::intValue).toArray();
        compatible=new BitSet[4][tiles.size()];
        for(int d=0;d<4;d++) {
            Map<List<BaseBlock>,BitSet> signatures=new HashMap<>();
            for(int t=0;t<tiles.size();t++) signatures.computeIfAbsent(edge(tiles.get(t),d,true),k -> new BitSet()).set(t);
            for(int t=0;t<tiles.size();t++) compatible[d][t]=(BitSet)signatures.getOrDefault(edge(tiles.get(t),d,false),new BitSet()).clone();
        }
    }

    private List<BaseBlock> edge(List<BaseBlock> tile,int d,boolean incoming) {
        List<BaseBlock> result=new ArrayList<>(n*(n-1));
        for(int y=0;y<n;y++) for(int x=0;x<n;x++) {
            int tx=x+DX[d],ty=y+DY[d];
            if(tx>=0&&tx<n&&ty>=0&&ty<n) result.add(tile.get((incoming?y:ty)*n+(incoming?x:tx)));
        }
        return result;
    }

    private List<BaseBlock> variant(BaseBlock[][] tile,int v,char axis) {
        int rotations=v/2; boolean mirror=(v&1)!=0;
        AffineTransform transform=new AffineTransform();
        if(mirror) transform=axis=='x'?transform.scale(1,-1,1):transform.scale(-1,1,1);
        transform=switch(axis) {case 'x' -> transform.rotateX(rotations*90); case 'z' -> transform.rotateZ(rotations*90); default -> transform.rotateY(-rotations*90);};
        List<BaseBlock> result=new ArrayList<>(Collections.nCopies(n*n,null));
        for(int y=0;y<n;y++) for(int x=0;x<n;x++) {
            int nx=mirror?n-1-x:x,ny=y;
            for(int r=0;r<rotations;r++) { int tmp=nx; nx=n-1-ny; ny=tmp; }
            BaseBlock block=transform.isIdentity()?tile[y][x]:BlockTransformExtent.transform(tile[y][x],transform);
            result.set(ny*n+nx,block);
        }
        return List.copyOf(result);
    }

    public BaseBlock[][] solve(int size,long seed) {
        if(size<n||size>128) throw new IllegalArgumentException("WFC output size must be between sample size and 128");
        int cells=size-n+1;
        Random random=new Random(seed);
        // A single shared budget bounds all retries, including impossible inputs.
        int[] budget={5_000_000};
        for(int attempt=0;attempt<8;attempt++) {
            BitSet[] domains=new BitSet[cells*cells];
            for(int i=0;i<domains.length;i++) { domains[i]=new BitSet(tiles.size()); domains[i].set(0,tiles.size()); }
            ArrayDeque<Integer> queue=new ArrayDeque<>();
            for(int i=0;i<domains.length;i++) queue.add(i);
            boolean possible=propagate(domains,cells,queue,budget);
            while(possible) {
                int chosen=-1,min=Integer.MAX_VALUE,ties=0;
                for(int i=0;i<domains.length;i++) {
                    int count=domains[i].cardinality();
                    if(count>1&&count<min) { chosen=i; min=count; ties=1; }
                    else if(count>1&&count==min&&random.nextInt(++ties)==0) chosen=i;
                }
                if(chosen<0) return render(domains,cells,size);
                int total=0;
                for(int bit=domains[chosen].nextSetBit(0);bit>=0;bit=domains[chosen].nextSetBit(bit+1)) total+=weights[bit];
                int pick=random.nextInt(total),tile=0;
                for(int bit=domains[chosen].nextSetBit(0);bit>=0;bit=domains[chosen].nextSetBit(bit+1)) {
                    pick-=weights[bit]; if(pick<0) { tile=bit; break; }
                }
                domains[chosen].clear(); domains[chosen].set(tile); queue.add(chosen);
                possible=propagate(domains,cells,queue,budget);
            }
        }
        throw new IllegalArgumentException("WFC could not solve this sample. Try -s, a smaller sample size, or a larger source selection");
    }

    private boolean propagate(BitSet[] domains,int size,ArrayDeque<Integer> queue,int[] budget) {
        boolean[] queued=new boolean[domains.length]; for(int i:queue) queued[i]=true;
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("WFC interrupted");
            int cell=queue.removeFirst(); queued[cell]=false; int x=cell%size,y=cell/size;
            if(domains[cell].isEmpty()) return false;
            for(int d=0;d<4;d++) {
                int nx=x+DX[d],ny=y+DY[d]; if(nx<0||ny<0||nx>=size||ny>=size) continue;
                BitSet allowed=new BitSet();
                for(int t=domains[cell].nextSetBit(0);t>=0;t=domains[cell].nextSetBit(t+1)) {
                    if(--budget[0]<0) throw new IllegalArgumentException("WFC complexity limit reached; reduce output/sample size or symmetry");
                    allowed.or(compatible[d][t]);
                }
                int next=ny*size+nx,count=domains[next].cardinality(); domains[next].and(allowed);
                if(domains[next].isEmpty()) return false;
                if(domains[next].cardinality()!=count&&!queued[next]) { queue.add(next); queued[next]=true; }
            }
        }
        return true;
    }

    private BaseBlock[][] render(BitSet[] domains,int cells,int size) {
        BaseBlock[][] output=new BaseBlock[size][size];
        for(int y=0;y<cells;y++) for(int x=0;x<cells;x++) {
            List<BaseBlock> tile=tiles.get(domains[y*cells+x].nextSetBit(0));
            for(int j=0;j<n;j++) for(int i=0;i<n;i++) output[y+j][x+i]=tile.get(j*n+i);
        }
        return output;
    }
}
