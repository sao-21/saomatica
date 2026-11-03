package fr.sao.saomatica.client;

import fr.sao.saomatica.mc.BlockStates;
import fr.sao.saomatica.mc.PreviewNet;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Aperçu fantôme : réception de la grille + rendu translucide des blocs visibles. */
public final class Ghost {
    private static final int LIGHT = 15728880;
    private static final double RANGE = 110;
    private static final int MAX_PER_FRAME = 16000;

    private static boolean active = false;
    private static BlockPos origin = BlockPos.ORIGIN;
    private static int sx, sy, sz, solid;
    private static int[] px = new int[0], py = new int[0], pz = new int[0];
    private static BlockState[] states = new BlockState[0];

    private static final Map<Integer, byte[]> PARTS = new HashMap<>();

    private Ghost() {}

    public static boolean active() { return active; }
    public static BlockPos origin() { return origin; }
    public static String info() { return sx + "x" + sy + "x" + sz + " — " + solid + " blocs"; }

    public static void clear() {
        active = false;
        PARTS.clear();
        px = py = pz = new int[0];
        states = new BlockState[0];
    }

    public static void setOrigin(BlockPos p) { origin = p; }

    public static void onChunk(PreviewNet.Chunk c) {
        if (c.index() == 0) PARTS.clear();
        PARTS.put(c.index(), c.data());
        if (PARTS.size() < c.total()) return;
        try {
            int len = 0;
            for (int i = 0; i < c.total(); i++) len += PARTS.get(i).length;
            byte[] all = new byte[len];
            int at = 0;
            for (int i = 0; i < c.total(); i++) { byte[] p = PARTS.get(i); System.arraycopy(p, 0, all, at, p.length); at += p.length; }
            PARTS.clear();
            build(c.sx(), c.sy(), c.sz(), inflate(all));
        } catch (IOException | RuntimeException e) {
            clear();
        }
    }

    private static byte[] inflate(byte[] in) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    private static int varint(DataInputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do { b = in.readUnsignedByte(); v |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
        return v;
    }

    private static void build(int gx, int gy, int gz, byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int np = varint(in);
        BlockState[] pal = new BlockState[np];
        for (int i = 0; i < np; i++) {
            String s = in.readUTF();
            BlockState st = i == 0 ? null : BlockStates.parse(s);
            pal[i] = st;
        }
        int n = gx * gy * gz;
        int[] data = new int[n];
        int i = 0;
        while (i < n) {
            int run = varint(in), v = varint(in);
            for (int k = 0; k < run && i < n; k++) data[i++] = v;
        }
        List<int[]> pts = new ArrayList<>();
        int count = 0;
        for (int y = 0; y < gy; y++) for (int z = 0; z < gz; z++) for (int x = 0; x < gx; x++) {
            int v = data[(y * gz + z) * gx + x];
            if (v == 0) continue;
            count++;
            if (pal[v] == null) continue;
            if (solidAt(data, gx, gy, gz, x + 1, y, z) && solidAt(data, gx, gy, gz, x - 1, y, z)
                && solidAt(data, gx, gy, gz, x, y + 1, z) && solidAt(data, gx, gy, gz, x, y - 1, z)
                && solidAt(data, gx, gy, gz, x, y, z + 1) && solidAt(data, gx, gy, gz, x, y, z - 1)) continue;
            pts.add(new int[]{x, y, z, v});
        }
        int m = pts.size();
        px = new int[m]; py = new int[m]; pz = new int[m];
        states = new BlockState[m];
        for (int k = 0; k < m; k++) {
            int[] p = pts.get(k);
            px[k] = p[0]; py[k] = p[1]; pz[k] = p[2]; states[k] = pal[p[3]];
        }
        sx = gx; sy = gy; sz = gz; solid = count;
        active = true;
    }

    private static boolean solidAt(int[] d, int gx, int gy, int gz, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) return false;
        return d[(y * gz + z) * gx + x] != 0;
    }

    /** Rendu translucide. Appelé après les couches translucides du monde. */
    public static void render(MatrixStack matrices, Vec3d cam) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        Alpha alpha = new Alpha(110);
        VertexConsumerProvider prov = layer -> {
            alpha.delegate = imm.getBuffer(TexturedRenderLayers.getEntityTranslucentCull());
            return alpha;
        };
        double r2 = RANGE * RANGE;
        int drawn = 0;
        for (int k = 0; k < px.length && drawn < MAX_PER_FRAME; k++) {
            double wx = origin.getX() + px[k], wy = origin.getY() + py[k], wz = origin.getZ() + pz[k];
            double dx = wx + 0.5 - cam.x, dy = wy + 0.5 - cam.y, dz = wz + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            matrices.push();
            matrices.translate(wx - cam.x, wy - cam.y, wz - cam.z);
            mc.getBlockRenderManager().renderBlockAsEntity(states[k], matrices, prov, LIGHT, OverlayTexture.DEFAULT_UV);
            matrices.pop();
            drawn++;
        }
        imm.draw();
    }

    /** Impose une opacité fixe à tous les sommets. */
    private static final class Alpha implements VertexConsumer {
        VertexConsumer delegate;
        private final int a;
        Alpha(int a) { this.a = a; }
        @Override public VertexConsumer vertex(float x, float y, float z) { delegate.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int alpha) { delegate.color(r, g, b, a); return this; }
        @Override public VertexConsumer texture(float u, float v) { delegate.texture(u, v); return this; }
        @Override public VertexConsumer overlay(int u, int v) { delegate.overlay(u, v); return this; }
        @Override public VertexConsumer light(int u, int v) { delegate.light(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { delegate.normal(x, y, z); return this; }
    }
}
