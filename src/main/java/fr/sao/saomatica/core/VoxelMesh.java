package fr.sao.saomatica.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grille de voxels creuse avec normales de surface (port de voxel_mesh.ts). */
public final class VoxelMesh {
    public static final class Voxel {
        public final int x, y, z;
        public final double[] colour; // r,g,b,a [0,1]
        public int collisions = 1;
        public final double[] normal = new double[3];
        public int normalSamples = 0;

        Voxel(int x, int y, int z, double[] colour) {
            this.x = x; this.y = y; this.z = z; this.colour = colour;
        }
    }

    // bits de visibilité de face (convention de l'appli web : North=+x, South=-x, East=+z, West=-z)
    public static final int F_UP = 1, F_DOWN = 2, F_NORTH = 4, F_EAST = 8, F_SOUTH = 16, F_WEST = 32;
    public static final int F_ALL = 63;

    private final Map<Long, Voxel> voxels = new HashMap<>();
    private final boolean average;
    public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    public VoxelMesh(boolean average) { this.average = average; }

    public static long key(int x, int y, int z) {
        return ((long) ((x + 1048576) & 0x1FFFFF) << 42) | ((long) ((y + 1048576) & 0x1FFFFF) << 21) | (long) ((z + 1048576) & 0x1FFFFF);
    }

    public List<Voxel> getVoxels() { return new ArrayList<>(voxels.values()); }
    public int count() { return voxels.size(); }
    public boolean has(int x, int y, int z) { return voxels.containsKey(key(x, y, z)); }
    public Voxel get(int x, int y, int z) { return voxels.get(key(x, y, z)); }

    public boolean isOpaque(int x, int y, int z) {
        Voxel v = get(x, y, z);
        return v != null && v.colour[3] == 1.0;
    }

    public int faceVisibility(int x, int y, int z) {
        int f = 0;
        if (!isOpaque(x, y + 1, z)) f += F_UP;
        if (!isOpaque(x, y - 1, z)) f += F_DOWN;
        if (!isOpaque(x + 1, y, z)) f += F_NORTH;
        if (!isOpaque(x - 1, y, z)) f += F_SOUTH;
        if (!isOpaque(x, y, z + 1)) f += F_EAST;
        if (!isOpaque(x, y, z - 1)) f += F_WEST;
        return f;
    }

    /** Ajoute un voxel ; normal peut être null. */
    public void add(int x, int y, int z, double[] colour, double[] normal) {
        if (colour[3] == 0) return;
        double[] un = null;
        if (normal != null) {
            double m = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            if (m > 1e-6) un = new double[] {normal[0] / m, normal[1] / m, normal[2] / m};
        }
        long k = key(x, y, z);
        Voxel v = voxels.get(k);
        if (v != null) {
            if (average) {
                int c = v.collisions;
                for (int i = 0; i < 4; i++) v.colour[i] = (v.colour[i] * c + colour[i]) / (c + 1);
                v.collisions++;
            }
            if (un != null) {
                v.normal[0] += un[0]; v.normal[1] += un[1]; v.normal[2] += un[2];
                v.normalSamples++;
            }
        } else {
            Voxel nv = new Voxel(x, y, z, colour.clone());
            if (un != null) { nv.normal[0] = un[0]; nv.normal[1] = un[1]; nv.normal[2] = un[2]; nv.normalSamples = 1; }
            voxels.put(k, nv);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
    }

    /** Flood fill depuis l'extérieur, remplit l'intérieur creux avec la couleur de surface la plus proche. */
    public int fillInterior() {
        int x0 = minX - 1, y0 = minY - 1, z0 = minZ - 1;
        int sx = maxX - minX + 3, sy = maxY - minY + 3, sz = maxZ - minZ + 3;
        boolean[] ext = new boolean[sx * sy * sz];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        ext[0] = true;
        q.add(new int[] {0, 0, 0});
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue;
                int idx = (nx * sy + ny) * sz + nz;
                if (ext[idx]) continue;
                if (has(nx + x0, ny + y0, nz + z0)) continue;
                ext[idx] = true;
                q.add(new int[] {nx, ny, nz});
            }
        }
        int filled = 0;
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            int idx = ((x - x0) * sy + (y - y0)) * sz + (z - z0);
            if (ext[idx] || has(x, y, z)) continue;
            add(x, y, z, nearestColour(x, y, z), null);
            filled++;
        }
        return filled;
    }

    private double[] nearestColour(int x, int y, int z) {
        for (int r = 1; r <= 8; r++)
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;
                Voxel v = get(x + dx, y + dy, z + dz);
                if (v != null) return v.colour.clone();
            }
        return new double[] {0.5, 0.5, 0.5, 1.0};
    }
}
