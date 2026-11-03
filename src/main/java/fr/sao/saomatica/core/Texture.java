package fr.sao.saomatica.core;

import java.awt.image.BufferedImage;

/** Échantillonnage de texture (port de texture.ts). Couleurs en [0,1]. */
public final class Texture {
    private final int w, h;
    private final int[] px;

    public Texture(BufferedImage img) {
        w = img.getWidth();
        h = img.getHeight();
        px = img.getRGB(0, 0, w, h, null, 0, w);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double correct(double t) {
        double r = t % 1.0;
        if (r < 0) r += 1.0;
        return r;
    }

    private double[] sample(int x, int y) {
        x = (int) clamp(x, 0, w - 1);
        y = (int) clamp(y, 0, h - 1);
        int p = px[y * w + x];
        return new double[] {
            ((p >> 16) & 255) / 255.0, ((p >> 8) & 255) / 255.0, (p & 255) / 255.0, ((p >>> 24) & 255) / 255.0
        };
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {
            a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t
        };
    }

    /** @return r,g,b,a en [0,1]. */
    public double[] getRGBA(double u, double v, boolean linear, boolean repeat) {
        if (!repeat) { u = clamp(u, 0, 1); v = clamp(v, 0, 1); }
        else { u = correct(u); v = correct(v); }
        v = 1.0 - v;
        if (!linear) return sample((int) Math.floor(u * w), (int) Math.floor(v * h));
        double x = u * w, y = v * h;
        int xl = (int) Math.floor(x), yu = (int) Math.floor(y);
        double fu = x - xl, fv = y - yu;
        double[] ab = lerp(sample(xl, yu), sample(xl + 1, yu), fu);
        double[] cd = lerp(sample(xl, yu + 1), sample(xl + 1, yu + 1), fu);
        return lerp(ab, cd, fv);
    }
}
