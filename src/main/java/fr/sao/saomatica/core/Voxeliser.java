package fr.sao.saomatica.core;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleConsumer;

/** Voxeliseur NCRB : rayons sur 3 axes + normales de sommets interpolées (port de normal-corrected-ray-voxeliser.ts). */
public final class Voxeliser {
    private static final double EPS = 0.0000001;
    private static final int MULTISAMPLE = 16;

    private Voxeliser() {}

    public static VoxelMesh voxelise(Mesh mesh, Options opt, DoubleConsumer progress) {
        VoxelMesh vm = new VoxelMesh(opt.overlapAverage);
        double[][] b0 = mesh.bounds();
        int ax = opt.constraintAxis == 'x' ? 0 : opt.constraintAxis == 'y' ? 1 : 2;
        double dim = b0[1][ax] - b0[0][ax];
        double scale = opt.size / dim;
        mesh.setScale(scale);
        try {
            double[][] b = mesh.bounds();
            double[] size = new double[3];
            double[] off = new double[3];
            for (int k = 0; k < 3; k++) {
                size[k] = Math.ceil(b[1][k]) - Math.floor(b[0][k]);
                off[k] = ((long) size[k]) % 2 == 0 ? 0.5 : 0.0;
            }
            int n = mesh.tris.size();
            for (int t = 0; t < n; t++) {
                if ((t & 1023) == 0 && progress != null) progress.accept((double) t / n);
                tri(mesh, mesh.tris.get(t), off, opt, vm);
            }
        } finally {
            mesh.clearScale();
        }
        if (opt.fillInterior) vm.fillInterior();
        return vm;
    }

    private static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    private static double[] norm(double[] a) {
        double m = len(a);
        return new double[] {a[0] / m, a[1] / m, a[2] / m};
    }

    private static void tri(Mesh mesh, Mesh.Tri tr, double[] off, Options opt, VoxelMesh vm) {
        double[] v0 = mesh.vertex(tr.p0), v1 = mesh.vertex(tr.p1), v2 = mesh.vertex(tr.p2);
        // normales de sommets (repli : normale de face)
        double[] faceN = norm(cross(sub(v0, v1), sub(v0, v2)));
        double[] n0 = vertexNormal(mesh, tr.n0, faceN), n1 = vertexNormal(mesh, tr.n1, faceN), n2 = vertexNormal(mesh, tr.n2, faceN);

        int[] lo = new int[3], hi = new int[3];
        for (int k = 0; k < 3; k++) {
            lo[k] = (int) Math.ceil(Math.min(v0[k], Math.min(v1[k], v2[k])));
            hi[k] = (int) Math.floor(Math.max(v0[k], Math.max(v1[k], v2[k])));
        }
        // axe 0 (x) : rayons sur (y,z) ; axe 1 (y) : (x,z) ; axe 2 (z) : (x,y)
        for (int axis = 0; axis < 3; axis++) {
            int a = axis == 0 ? 1 : 0;          // premier axe de la grille de rayons
            int c = axis == 2 ? 1 : 2;          // second axe
            for (double p = lo[a] - off[a]; p <= hi[a] + off[a]; p++) {
                for (double q = lo[c] - off[c]; q <= hi[c] + off[c]; q++) {
                    double[] origin = new double[3];
                    origin[axis] = lo[axis] - 1;
                    origin[a] = p;
                    origin[c] = q;
                    hit(mesh, tr, axis, origin, v0, v1, v2, n0, n1, n2, off, opt, vm);
                }
            }
        }
    }

    private static double[] vertexNormal(Mesh mesh, int idx, double[] fallback) {
        if (idx >= 0 && idx < mesh.normals.size()) return mesh.normals.get(idx).clone();
        return fallback.clone();
    }

    private static void hit(Mesh mesh, Mesh.Tri tr, int axis, double[] origin, double[] v0, double[] v1, double[] v2,
                            double[] n0, double[] n1, double[] n2, double[] off, Options opt, VoxelMesh vm) {
        double[] dir = {0, 0, 0};
        dir[axis] = 1;
        double[] e1 = sub(v1, v0), e2 = sub(v2, v0);
        double[] h = cross(dir, e2);
        double a = dot(e1, h);
        if (a > -EPS && a < EPS) return;
        double f = 1.0 / a;
        double[] s = sub(origin, v0);
        double u = f * dot(s, h);
        if (u < 0.0 || u > 1.0) return;
        double[] q = cross(s, e1);
        double v = f * dot(dir, q);
        if (v < 0.0 || u + v > 1.0) return;
        double t = f * dot(e2, q);
        if (t <= EPS) return;
        double[] p = {origin[0] + dir[0] * t, origin[1] + dir[1] * t, origin[2] + dir[2] * t};

        // « Move transition away from normal » (normalise n0 en place comme l'original)
        double m0 = len(n0);
        n0[0] /= m0; n0[1] /= m0; n0[2] /= m0;
        for (int k = 0; k < 3; k++) p[k] = p[k] - n0[k] * 0.5 + off[k];
        double[] vp = p.clone();
        vp[axis] = Math.floor(p[axis] + 0.5);

        double[] colour = voxelColour(mesh, tr, v0, v1, v2, vp, opt.multisample);

        double w = 1.0 - u - v;
        double[] in = new double[3];
        for (int k = 0; k < 3; k++) in[k] = w * n0[k] + u * n1[k] + v * n2[k];
        double im = len(in);
        if (im > 0) { in[0] /= im; in[1] /= im; in[2] /= im; }
        // Math.round JS (arrondi vers +inf sur .5) : floor(x + 0.5)
        vm.add((int) Math.floor(vp[0] + 0.5), (int) Math.floor(vp[1] + 0.5), (int) Math.floor(vp[2] + 0.5), colour, in);
    }

    private static double area(double[] a, double[] b, double[] c) {
        double x = len(sub(a, b)), y = len(sub(b, c)), z = len(sub(c, a));
        double p = (x + y + z) / 2;
        double r = p * (p - x) * (p - y) * (p - z);
        return r > 0 ? Math.sqrt(r) : 0;
    }

    private static double[] voxelColour(Mesh mesh, Mesh.Tri tr, double[] v0, double[] v1, double[] v2, double[] loc, boolean multi) {
        Mesh.Material mat = mesh.materials.get(tr.material);
        if (mat == null) mat = new Mesh.Material();
        if (mat.texture == null || tr.t0 < 0) return new double[] {mat.colour[0], mat.colour[1], mat.colour[2], 1.0};
        double[] uv0 = uv(mesh, tr.t0), uv1 = uv(mesh, tr.t1), uv2 = uv(mesh, tr.t2);
        int n = multi ? MULTISAMPLE : 1;
        double[] acc = new double[3];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            double[] l = {loc[0] + rnd.nextDouble() - 0.5, loc[1] + rnd.nextDouble() - 0.5, loc[2] + rnd.nextDouble() - 0.5};
            double a01 = area(v0, v1, l), a12 = area(v1, v2, l), a20 = area(v2, v0, l);
            double tot = a01 + a12 + a20;
            double w0 = a12 / tot, w1 = a20 / tot, w2 = a01 / tot;
            double u = uv0[0] * w0 + uv1[0] * w1 + uv2[0] * w2;
            double vv = uv0[1] * w0 + uv1[1] * w1 + uv2[1] * w2;
            double[] c;
            if (Double.isNaN(u) || Double.isNaN(vv)) c = new double[] {1, 0, 1, 1};
            else c = mat.texture.getRGBA(u, vv, mat.linear, mat.repeat);
            acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2];
        }
        return new double[] {acc[0] / n, acc[1] / n, acc[2] / n, 1.0};
    }

    private static double[] uv(Mesh mesh, int idx) {
        return idx >= 0 && idx < mesh.uvs.size() ? mesh.uvs.get(idx) : new double[] {0, 0};
    }
}
