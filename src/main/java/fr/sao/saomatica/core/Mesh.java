package fr.sao.saomatica.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maillage triangulaire (port de mesh.ts). */
public final class Mesh {
    public static final class Material {
        public double[] colour = {0.8, 0.8, 0.8, 1.0}; // solide
        public Texture texture;                        // null => solide
        public boolean linear = true;
        public boolean repeat = false;
    }

    public static final class Tri {
        public int p0, p1, p2;
        public int t0 = -1, t1 = -1, t2 = -1;
        public int n0 = -1, n1 = -1, n2 = -1;
        public String material = "DEFAULT_UNASSIGNED";
    }

    public final List<double[]> vertices = new ArrayList<>();
    public final List<double[]> normals = new ArrayList<>();
    public final List<double[]> uvs = new ArrayList<>();
    public final List<Tri> tris = new ArrayList<>();
    public final Map<String, Material> materials = new HashMap<>();

    private double scale = 1.0;
    private boolean transformed = false;

    /** Centre le maillage et le met à l'échelle pour que sa hauteur (y) vaille 1. */
    public void process() {
        if (vertices.isEmpty()) throw new IllegalStateException("Aucun sommet dans le modèle");
        if (tris.isEmpty()) throw new IllegalStateException("Aucun triangle dans le modèle");
        // matériaux manquants -> gris
        for (Tri t : tris) materials.computeIfAbsent(t.material, k -> new Material());
        // UV hors [0,1] => repeat
        Map<String, Boolean> oob = new HashMap<>();
        for (Tri t : tris) {
            if (t.t0 < 0 || oob.containsKey(t.material)) continue;
            for (int idx : new int[] {t.t0, t.t1, t.t2}) {
                if (idx >= uvs.size()) continue;
                double[] uv = uvs.get(idx);
                if (uv[0] < 0 || uv[0] > 1 || uv[1] < 0 || uv[1] > 1) { oob.put(t.material, true); break; }
            }
        }
        for (Map.Entry<String, Material> e : materials.entrySet()) {
            if (e.getValue().texture != null) e.getValue().repeat = oob.containsKey(e.getKey());
        }
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] v : vertices) for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], v[k]); mx[k] = Math.max(mx[k], v[k]); }
        double[] c = {(mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2};
        double sizeY = mx[1] - mn[1];
        double f = 1.0 / sizeY;
        if (Double.isNaN(f) || Double.isInfinite(f)) throw new IllegalStateException("Impossible de mettre le modèle à l'échelle (hauteur nulle)");
        for (double[] v : vertices) for (int k = 0; k < 3; k++) v[k] = (v[k] - c[k]) * f;
    }

    public void setScale(double s) { scale = s; transformed = true; }
    public void clearScale() { scale = 1.0; transformed = false; }

    public double[] vertex(int idx) {
        double[] v = vertices.get(idx);
        if (!transformed) return new double[] {v[0], v[1], v[2]};
        return new double[] {v[0] * scale, v[1] * scale, v[2] * scale};
    }

    /** Bornes (min,max) en tenant compte de l'échelle courante. */
    public double[][] bounds() {
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] v0 : vertices) {
            for (int k = 0; k < 3; k++) {
                double v = transformed ? v0[k] * scale : v0[k];
                mn[k] = Math.min(mn[k], v);
                mx[k] = Math.max(mx[k], v);
            }
        }
        return new double[][] {mn, mx};
    }
}
