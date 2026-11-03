package fr.sao.saomatica.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;

/** Pipeline complet : fichier 3D -> grille de blocs prête à placer ou exporter. */
public final class Converter {
    private Converter() {}

    public static Mesh loadMesh(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase();
        Mesh m;
        if (n.endsWith(".obj")) m = ObjImporter.load(file);
        else if (n.endsWith(".glb")) m = GlbImporter.load(file);
        else throw new IOException("Format non géré (utilise .obj ou .glb) : " + n);
        m.process();
        return m;
    }

    public static BlockGrid convert(Path file, Options opt, Predicate<String> exists, DoubleConsumer progress) throws IOException {
        Mesh mesh = loadMesh(file);
        VoxelMesh vm = Voxeliser.voxelise(mesh, opt, progress == null ? null : p -> progress.accept(p * 0.5));
        return build(vm, opt, exists, progress == null ? null : p -> progress.accept(0.5 + p * 0.5));
    }

    public static BlockGrid build(VoxelMesh vm, Options opt, Predicate<String> exists, DoubleConsumer progress) {
        if (vm.count() == 0) throw new IllegalStateException("Aucun voxel généré (modèle vide ?)");
        List<BlockAssigner.Assigned> blocks = BlockAssigner.assign(vm, opt, exists, progress);
        ShapeEngine engine = new ShapeEngine(vm, opt, exists);
        int sx = vm.maxX - vm.minX + 1, sy = vm.maxY - vm.minY + 1, sz = vm.maxZ - vm.minZ + 1;
        BlockGrid grid = new BlockGrid(sx, sy, sz);
        for (BlockAssigner.Assigned a : blocks) {
            String state = engine.shape(a.name, a.voxel);
            grid.set(a.voxel.x - vm.minX, a.voxel.y - vm.minY, a.voxel.z - vm.minZ, state);
        }
        return grid;
    }
}
