# Building WorldPainter
## Installing dependencies
WorldPainter needs some dependencies that are not in public Maven repos and cannot be distributed on WorldPainter's private repo due to their licence. You need to install these dependencies into your local Maven repo manually:
### JIDE Docking Framework
For the docks, WorldPainter uses the [JIDE Docking Framework](https://www.jidesoft.com/products/dock.htm), which is a commercial product. For development, you can download an evaluation version of the product [here](https://www.jidesoft.com/evaluation/), with user ID and password documented [here](https://www.jidesoft.com/forum/viewtopic.php?t=10) (note that you need to create a forum account to access the second link). The evaluation version will expire after two months, but you can keep downloading it again whenever it expires for two more months of development time.

Once you have your copy, install the `jide-common.jar`, `jide-dock.jar` and `jide-plaf-jdk7.jar` files in your local Maven repository. If necessary, update the version numbers in the pom.xml of the WPGUI module if you downloaded a different version!

## Set up Maven toolchain
WorldPainter uses the [Maven toolchain framework](https://maven.apache.org/guides/mini/guide-using-toolchains.html) to find the JDK it needs. You need to follow the instructions on that page to configure a toolchain of type jdk and version 17 pointing to a Java 17 JDK. Note that it has not been tested whether WorldPainter will run correctly on older Java versions if you substitute a newer JDK for version 17, although in theory that should work.

## Build WorldPainter
Once all dependencies are installed and the toolchains set up you can build WorldPainter from the command line or using your favourite IDE by invoking the `install` goal on the `WorldPainter` module. There are some rudimentary tests, but they take a while to run and don't contribute much, so I recommend skipping them by adding `-DskipTests=true`.

## Run WorldPainter
Once it is built, you can run WorldPainter by invoking the `exec:exec` goal on the `WPGUI` module, or by running the main class: `org.pepsoft.worldpainter.Main`.

## Develop WorldPainter
For a few pointers, pitfalls and gotchas about developing WorldPainter, see [this page](https://www.worldpainter.net/trac/wiki/DevelopingWorldPainter).

## More details
For a more detailed description of the build process, see: https://www.worldpainter.net/doc/building.

## Building Welt with native acceleration
Welt adds an optional native (Rust) acceleration layer. To build with it, use the Maven profile `-Pnative`: it invokes `cargo build --release` (via exec-maven-plugin) and copies the resulting native library (e.g. `welt_core.dll`) into `WPCore/src/main/resources/natives/<os>-<arch>/`, after which the build proceeds normally.

Without `-Pnative`, nothing changes: the build remains 100% Java and WorldPainter behaves exactly as before (native acceleration is opt-in at runtime via the `wp.native.gen`, `wp.native.render` and `wp.native.export` system properties, which default to `false` and fall back silently to the Java code path).

The native Frost and Resources exporters currently require an additional explicit switch (`-Dwp.native.export.frost=true` or `-Dwp.native.export.resources=true`, respectively) alongside `-Dwp.native.export=true`. Their measured export paths remain slower than Java, so the general export switch keeps them on Java while enabling the other native export operations.

With `-Dwp.native.gen=true`, non-dithered terrain painting can filter and paint compact tile planes in a single native transaction. The automatic path currently selects cached built-in brushes with slope filters and bounding-box areas of at least 32,768 cells; brush rectangles are bounded to 256 by 256 cells. `-Dwelt.native.filteredTerrain=false` disables it, while `true` also selects smaller supported footprints and other built-in filter combinations for comparison. Custom filters, custom tile/dimension behavior, dithering, removal and missing native symbols keep the Java path. The direct buffer is reused per worker and capped at 4 MiB; the measured speed gain currently comes with increased allocations and process memory.

Filtered nibble-layer painting and removal can use the same worker buffer with `-Dwelt.native.filteredLayers=true` alongside `-Dwp.native.gen=true`. This path remains explicit because complete-operation measurements show higher memory use. It preserves the original distinction between rounded one-tile removal and truncated multi-tile removal, and replays unsupported unclamped values through Java. Terrain and numeric layers share the versioned WFPT protocol and one native entry point; the former terrain entry points remain compatible.

The same explicit flag also enables non-dithered filtered bit-layer painting and removal, for both block bits and bits shared by a chunk. Predicates reading the output layer use the same packed plane as the writes. Removing an absent bit layer preserves its absent storage and notification behavior; dithering retains the Java random stream. WFPT v3 declares the bit output type without adding a new JNI entry point.

Filtered discrete nibble/byte painting (including annotations and numbered biomes) also uses this explicit flag and shared transaction. WFPT v4 writes constant values independently of the terrain palette, preserving biome values through 255, adjacent annotation nibbles and the removal value captured by the paint constructor. Default writes to absent storage do not create a layer; invalid targets and dithering retain Java behavior.

For the Rust toolchain setup, exact build commands and details, see `docs/welt/` (start with `docs/welt/README.md` and `docs/welt/CHARTE-ORCHESTRATION.md`).
