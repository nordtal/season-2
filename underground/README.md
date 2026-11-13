# underground

A command line tool that replaces the underground of the custom overworld with one the game
generates, offline and on copies of the region files. It never writes into the world it reads: the
result is a third folder. The stack does not run it and no release ships it.

The custom overworld has little below its surface. The tool generates a second world with the map's
seed in which the terrain is solid rock up to the build limit, so the game carves its caves, ore
veins, mineshafts, trial chambers and ancient cities through all of it. Then it joins the two: the
map stays from the surface down to a crust that follows the distance to the open sky in three
dimensions, everything somebody built stays with the rock and the open space around it, and the
generated world fills the rest.

| Package      | What it does                                                                      |
| ------------ | --------------------------------------------------------------------------------- |
| `anvil`      | Reads and writes region files and the block and biome storage of a chunk.         |
| `block`      | Sorts block names into ground, fluid, plant, built and ambiguous, from its lists. |
| `transplant` | Decides for every cell which world it comes from, and writes the joined regions.  |
| `server`     | Runs a scratch server of the game to upgrade a copy, generate a world and verify. |
| `check`      | Counts blocks, measures the cut and draws cross-sections.                         |

## A run

Build it with `sh gradlew :underground:installDist`; the commands below are
`underground/build/install/underground/bin/underground <command> <options>`. A world is always the
folder that holds `region` and `entities`, in a current world `<level>/dimensions/minecraft/overworld`.
The commands that start a server need a server jar of the map's version with its `libraries` beside
it, `--eula true`, and a free `--work` folder each. Regions are named as `--regions x1,z1,x2,z2`,
both corners included; without the option a command takes every region file of the map.

1. **Copy the map** while its server is stopped, or take it from a backup: `region` and `entities`.
2. **`upgrade --work <dir> --server <jar> --map <copy> --out <map>`** brings every chunk to the
   newest data version found in the copy, with the game's own upgrade. It needs the server jar of
   the game itself, since Paper has no forced upgrade. The transplant refuses to join chunks of two
   versions.
3. **`generate --work <dir> --server <jar> --data <jar> --seed <seed> --regions …`** writes the data
   pack, creates the world in `<dir>/world` and generates every chunk of the regions. `--data` is the
   jar that holds the game's `data/` folder: `versions/<version>/paper-<version>.jar` beside a Paper
   jar that has started once.
4. **`transplant --map <map> --parallel <dir>/world/dimensions/minecraft/overworld --out <result>`**
   writes the joined `region` and `entities`. It prints what it kept, filled, closed and opened, and
   a line per entrance with the place where the map's cave ends, the middle of the tunnel and its end.
5. **`check --map <map> --parallel … --out <result>`** measures the cut without the decisions of the
   transplant and exits with 1 when a measure is over its limit: no built block changed, no fluid
   beside open space of the other world, and of the ceilings and floors of new rock, where it closes
   a generated cave or ends a cave of the map, no larger share in flat faces (25 blocks or more, and
   200 or more) than `--flat-share` times the share among the caves the game made, 1.5 by default.
   It prints where the largest flat face of each kind lies.
6. **`verify --work <dir> --server <jar> --world <result> --seed <seed> --datapacks <dir> --map <map>`**
   loads every chunk of the result on a scratch server, reads its log for errors, counts the chunks
   the server lit again and asks for the structures: a mineshaft and a trial chamber inside the area,
   no stronghold. `--datapacks` is the folder with the data packs of the map, which name its biomes
   and structures; without them the server complains about every chunk that holds one. With `--map`,
   a complaint about the data of a block entity that the result holds just as the map does is the
   map's own and counted apart: the map has spawners and suspicious sand from older versions that
   the game reads with a warning.
7. **`slice --out <png> --worlds map=<map>,result=<result> --line 4000,2200:4400,2200`** draws the
   worlds one below the other along a line of `x,z` corners, to look at a place before and after.
   `--y 20..120` limits the heights and `--scale 4` sets the pixels per block.

The points of interest and the light are not part of the result: the game rebuilds both when it
loads a chunk without them. Copy the result over a copy of the world, never over the world itself,
and delete the `poi` files of the regions it replaced.

## What stays and what is replaced

A block of the map is ground when `block/terrain.txt` names it, a plant or the like when
`block/loose.txt` or one of the name endings in `Blocks` does, and built otherwise, so a block
nobody thought of is kept rather than lost. `block/ambiguous.txt` names blocks that are both, such
as cobblestone: one of them is built when a built block is within `--ambiguous-reach` through its
like, and ground otherwise. `histogram --map <map> --out <tsv>` counts every block of a map, in all
and deep below the surface; the lists were written from it.

| Option              | Default | What it sets, in blocks                                                        |
| ------------------- | ------- | ------------------------------------------------------------------------------ |
| `--crust`           | 8       | How deep below the outside the map stays.                                      |
| `--outside-reach`   | 24      | How far the outside follows open space under an overhang or into a cave mouth. |
| `--taper`           | 8       | Over how many of those blocks the followed space narrows to nothing.           |
| `--shell`           | 5       | How much rock stays around a built block.                                      |
| `--protected-reach` | 24      | How far the open space around something built stays open.                      |
| `--wall`            | 3       | How much rock stays around that open space.                                    |
| `--band`            | 12      | Below the kept zone, the depth over which a generated cave closes.             |
| `--roughness`       | 1.5     | How far that closing rock rises and falls, in a fine and a coarse wave.        |
| `--top`             | 236     | The height above which the map always stays.                                   |
| `--entrances`       | yes     | Whether a cave mouth of the map is joined to a generated cave near it.         |

- **No flat ceiling.** A generated cave that runs into the kept zone closes in its own shape: the
  nearer the zone, the wider the passage that still fills with rock.
- **No cut cave mouth.** A cave of the map stays open as far as the outside reaches into it, narrows
  over the last blocks and is rock behind that, back to where the generated world begins.
- **An entrance** joins a cave of the map that went on below the kept zone to a generated cave
  within 40 blocks: a bent tunnel as wide as the cave's end. It is opened only when a player can walk
  it, so no stretch of five blocks is steeper than about a block per block, and when it touches
  nothing built, no fluid and no ground close below the open sky. A cave without such a tunnel ends
  in rock.
- **Fluids** of one world never touch open space of the other: next to the kept zone every generated
  cave closes, water and lava with it, and no tunnel is opened beside a fluid.
- **Structures.** The data pack leaves only mineshafts, trial chambers and ancient cities, so the
  result has no stronghold of the game. A chest minecart of a mineshaft comes with its chunk.
- **A chunk the map or the generated world lacks** stays as the map has it, with its neighbours
  closed against it.
