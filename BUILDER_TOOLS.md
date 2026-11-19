# Builder tools

Independent implementations inspired by the public [Arceon tool documentation](https://arceon.gitbook.io/arceon-wiki).
Arceon is not required. These are not binary ports of its proprietary plugin.
Procedural fields and experimental tools have the behavior described here; their
output is not intended to match Arceon pixel for pixel.

Commands use FAWE's queued command execution and injected edit session. They use
normal undo/history and inherit the masks, protection and change limits active in
the existing edit pipeline.
Terrain seeds are per player and reset on disconnect.

## Block families and mods

Family matching uses the live server block registry, not a fixed vanilla list.
Type replacement retains stairs, slabs, walls, fences and other recognized shapes,
and copies compatible state values such as facing, half and waterlogged.

Matches prefer the source namespace, then Minecraft, then an unambiguous counterpart
in another mod. Use `othermod:marble` to choose a particular namespace. Missing or
ambiguous counterparts stay unchanged. `cobble` is an alias for `cobblestone`.
This is registry-name matching, not a universal material classification: unusually
named mod families may need explicit IDs or the mod author's naming convention.

Color replacement supports the 16 dye names in registered block names or a `color`
state property. It does not analyze custom textures or find visually similar blocks.
Compatible block-entity NBT is retained only when the original block-entity type
supports the destination state. Incompatible block-entity data is dropped.

## Commands

| Command | Behavior |
|---|---|
| `//colorreplace <from> <to>` / `//cr` | Recolor matching families in a cuboid selection |
| `//typereplace <from> <to>` / `//tr` | Replace material families in a cuboid selection |
| `//wax` | Wax copper and registered modded waxed counterparts in a cuboid |
| `//tgseed <seed>` | Set your terrain seed for `//terragen ... -s` |
| `//terragen <noise> [pattern] [height] [zoom] [wave]` / `//tg` | Generate terrain clipped to the actual selection |
| `//wfc <size> [symmetry] [sample]` | Learn a planar texture and generate a square from it |

### Replacement menus

Run `//tr` or `//cr` without arguments to open a vanilla inventory menu. Select a
cuboid region first. The long command names also open menus without arguments.

1. Left-click the source material or color. It gains an enchantment glint.
2. Left-click a different destination. The menu closes and queues the replacement
   through the normal FAWE command pipeline, including undo.

Closing the menu before the second choice cancels without editing. Clicking the
highlighted source again, its footer preview, or **Reset source** clears the draft.
**Cancel replacement** also closes without editing. Source selection survives page
changes and the type menu's mod filter, so source and target can come from different
mods. Item tooltips show the exact family key and the current step.

The menus are display-only: shift-click, dragging, number-key swaps, dropping and
inventory clicks do not move items. The color menu lists all 16 dye colors; the type
menu lists registered families and prefers a full-block icon when available. Families
without an item use a bricks icon with their actual name and ID. An incomplete or
changed selection cancels submission. Permissions are checked again when submitting.

Existing arguments and flags remain available; `//tr -f` and `//cr -g` open menus
with those flags retained. Use `//tr oak cobble` or `//cr red blue` to skip the menu.

Examples:

```text
//colorreplace red blue
//tr oak cobble
//tr limestone othermod:marble
//wax
//tgseed 12345
//terragen fractal othermod:marble 48 32 0 -s -v
//terragen ridged #hotbar 32 24 0 -e
//wfc 32 8 3 -s
```

Permissions are `arceon.tool.colorreplace`, `arceon.tool.typereplace`,
`arceon.tool.wax`, `arceon.tool.terragen` and `arceon.tool.wfc`.
`//tgseed` uses the terrain permission. Fabric's configured permission provider
applies, including its normal operator fallback.

Flags:

- Color replace: `-b` skips banners; `-g` skips glazed terracotta.
- Type replace: `-f` skips walls/fences/gates; `-b` skips regular full blocks;
  `-d` skips double slabs and slab families with `smooth` in their name.
- Terragen: `-s` reuses your seed; `-v` fades the selection edges; `-e` adds terrain
  above existing blocks; `-n` uses eight octaves; `-r` maximizes cellular jitter.
- WFC: `-s` treats the input sample as seamless.

### Terrain

Noise types: `simplex`, `fractal`, `billowy`, `ridged`, `turbulence`, `electric`,
`euclidean`, `manhattan`, `natural`, `poly`. Defaults: stone, selection height,
zoom 32, wave 0. Height must fit within the selection. Larger zoom stretches the
field. Slab patterns use double slabs below the surface and optional half-slab caps;
snow patterns use eight layers below the surface and partial caps. Selection masks
and world bounds clip the result. The generator fills terrain; it does not clear
existing blocks above the generated height.

The explicit pattern position is required before height/zoom/wave. Old shorthand
examples that omit a pattern while supplying numeric height arguments are not supported.

### Wave function collapse

Select a one-block-thick cuboid sample in XZ, XY or YZ. WFC learns overlapping tiles,
including modded block states and block-entity data. It solves the output before any
writes; contradiction or complexity errors leave the world untouched.

The output starts at the selection's minimum corner in the same plane and can
extend beyond the sample selection. `<size>` is the square's side length, up to 128.
`[sample]` is the overlapping tile side length, 1–8, default up to 3. It must fit
inside the source. `[symmetry]` is 1–8, default 1: identity, mirror, then successive
quarter turns and their mirrors. State/NBT transforms use WorldEdit's block transform.

Limits: source area 4096 blocks, 512 distinct tiles, eight solve attempts and a shared
propagation-work budget. Use a smaller tile size/symmetry or a richer/seamless source
if the sample cannot tile the requested output. This implementation generates planar
textures, not three-dimensional structures.

## Masks

Use these in `//replace`, `//gmask` or brush masks. Existing FAWE aliases are kept;
`#arcnoise` provides the Arceon-style noise argument form because FAWE already has
its own `#noise` syntax.

| Syntax | Behavior |
|---|---|
| `#cc[color]`, `#color[color]` | Named dye family/property |
| `#type[material]`, `#tr[material]` | Material-name family |
| `y[min][max]` | Inclusive Y range |
| `#above[mask][distance=1]` | Target mask below this position |
| `#below[mask][distance=1]` | Target mask above this position |
| `#prox[mask][distance=1]` | Target mask within vertical distance |
| `#prox3D[mask][distance=1]` | Target mask within spherical distance |
| `#arcangle[min][max][range=1]` | Surface-normal angle, 0 horizontal to 90 vertical |
| `#ambient[min=2]` | Non-air surface with at least this many solid cardinal neighbors |
| `#ygradient[min][max]` | Deterministic probability ramp from 0 to 100% over Y |
| `#turbulence[zoom=1][coverage=50]` / `#turb` | Turbulent noise field |
| `#electric[zoom=1][coverage=50]` / `#elec` | Ridged electric field |
| `#fractal[zoom=1][coverage=50]` / `#frac` | Fractal field |
| `#crack[zoom=1][coverage=50][jitter=70]` | Cellular distance-gap field |
| `#cell[zoom=1][coverage=50][jitter=70]` | Cellular values |
| `#voronoi[zoom=1][jitter=50]` / `#vor` | Cellular borders |
| `#arcnoise[seed][type][threshold]` | Seeded noise, threshold in −255…255 |

Percentages accept `50` or `50%`. Mask zoom is measured in multiples of 16 blocks.
Vertical distances are limited to 256; 3D distance to 16; normal range to 2.
These limits keep per-block searches bounded.

The extended `#arcnoise` form accepts `[mode][interpolation-or-distance][octaves][frequency][jitter]`.
Supported type names include simplex/perlin/cubic, their fractal variants, and cellular.
Perlin/cubic names use this port's simplex field. `billow` and `rigidmulti` reshape
the fractal field; `cellvalue`, `distance` and `distance2` select nearest-cell values,
nearest distances or second-nearest distances. Natural/Manhattan/Euclidean select
the cellular metric. Hermite/quintic apply smooth curves to the field values.
These compatibility controls are not FastNoise parity.

## Patterns

| Syntax | Behavior |
|---|---|
| `#cc[color]` | Recolor the existing block's named family |
| `#type[material]`, `#tr[material]` | Retype the existing block, retaining shape |
| `#invert` | Closest inverse dye color within the same named family |
| `#blend[mask]` | Deterministically choose a matching cardinal neighbor; default non-air |
| `#dp[data]` | Alternate lower/upper double-plant layers; data 0–5 selects sunflower, lilac, tall grass, large fern, rose bush, peony |
| `#turbulence[scale][palette][seed=0]` / `#turb` | Noise-indexed palette |
| `#electric[scale][palette][seed=0]` / `#elec` | Noise-indexed palette |
| `#fractal[scale][palette][seed=0]` / `#frac` | Noise-indexed palette |
| `#arcvor[scale][palette][seed=0]` | Cellular-distance palette |
| `#cell[scale][palette][jitter=50][seed=0]` | Cellular-value palette |

Noise palettes accept existing patterns, including `#hotbar` and modded block IDs.
Percentage weights in comma palettes are retained by FAWE's weighted sampler.
Double plants need paired vertical
layers in the selection; the pattern does not write outside the selection or fix
Minecraft survival/support rules. Existing FAWE RGB `#color` remains unchanged.

```text
//replace #type[limestone] #type[othermod:marble]
//replace #cc[red] #cc[blue]
//replace #prox[gold_block][5] #fractal[12][#hotbar][12345]
//replace y[40][65] #cell[10][othermod:stone,othermod:marble][70][42]
```
