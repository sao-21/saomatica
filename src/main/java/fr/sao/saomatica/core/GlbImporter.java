package fr.sao.saomatica.core;

import fr.sao.saomatica.core.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Import .glb minimal (géométrie + couleur de base + texture de base). Les transformations de nœuds sont ignorées. */
public final class GlbImporter {
    private GlbImporter() {}

    public static Mesh load(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46546C67) throw new IOException("Pas un fichier .glb valide");
        int total = b.getInt(8);
        int off = 12;
        Map<String, Object> json = null;
        ByteBuffer bin = null;
        while (off < total) {
            int len = b.getInt(off);
            int type = b.getInt(off + 4);
            int start = off + 8;
            if (type == 0x4E4F534A) {
                json = Json.obj(Json.parse(new String(b.array(), start, len, StandardCharsets.UTF_8)));
            } else if (type == 0x004E4942) {
                bin = ByteBuffer.wrap(b.array(), start, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            off = start + len + ((4 - (len % 4)) % 4);
        }
        if (json == null || bin == null) throw new IOException("GLB incomplet (JSON ou BIN manquant)");

        List<Object> accessors = Json.arr(json.get("accessors"));
        List<Object> views = Json.arr(json.get("bufferViews"));
        List<Object> materials = json.containsKey("materials") ? Json.arr(json.get("materials")) : List.of();
        List<Object> textures = json.containsKey("textures") ? Json.arr(json.get("textures")) : List.of();
        List<Object> images = json.containsKey("images") ? Json.arr(json.get("images")) : List.of();

        Mesh mesh = new Mesh();
        // matériaux
        for (int m = 0; m < materials.size(); m++) {
            Mesh.Material mm = new Mesh.Material();
            Map<String, Object> mat = Json.obj(materials.get(m));
            if (mat.containsKey("pbrMetallicRoughness")) {
                Map<String, Object> pbr = Json.obj(mat.get("pbrMetallicRoughness"));
                if (pbr.containsKey("baseColorFactor")) {
                    List<Object> f = Json.arr(pbr.get("baseColorFactor"));
                    mm.colour = new double[] {Json.num(f.get(0)), Json.num(f.get(1)), Json.num(f.get(2)), 1.0};
                }
                if (pbr.containsKey("baseColorTexture")) {
                    int ti = (int) Json.num(Json.obj(pbr.get("baseColorTexture")).get("index"));
                    int ii = (int) Json.num(Json.obj(textures.get(ti)).get("source"));
                    Map<String, Object> img = Json.obj(images.get(ii));
                    if (img.containsKey("bufferView")) {
                        Map<String, Object> v = Json.obj(views.get((int) Json.num(img.get("bufferView"))));
                        int vo = v.containsKey("byteOffset") ? (int) Json.num(v.get("byteOffset")) : 0;
                        int vl = (int) Json.num(v.get("byteLength"));
                        byte[] data = new byte[vl];
                        ByteBuffer d = bin.duplicate();
                        d.position(vo);
                        d.get(data, 0, vl);
                        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
                        if (bi != null) { mm.texture = new Texture(bi); mm.repeat = true; }
                    }
                }
            }
            mesh.materials.put("MAT" + m, mm);
        }
        Mesh.Material none = new Mesh.Material();
        none.colour = new double[] {1, 1, 1, 1};
        mesh.materials.put("NONE", none);

        for (Object mo : Json.arr(json.get("meshes"))) {
            for (Object po : Json.arr(Json.obj(mo).get("primitives"))) {
                Map<String, Object> prim = Json.obj(po);
                int mode = prim.containsKey("mode") ? (int) Json.num(prim.get("mode")) : 4;
                if (mode != 4) continue;
                Map<String, Object> attrs = Json.obj(prim.get("attributes"));
                if (!attrs.containsKey("POSITION")) continue;
                int vBase = mesh.vertices.size(), nBase = mesh.normals.size(), tBase = mesh.uvs.size();
                float[] pos = readFloats(accessors, views, bin, (int) Json.num(attrs.get("POSITION")), 3);
                for (int i = 0; i < pos.length; i += 3) mesh.vertices.add(new double[] {pos[i], pos[i + 1], pos[i + 2]});
                boolean hasN = attrs.containsKey("NORMAL"), hasT = attrs.containsKey("TEXCOORD_0");
                if (hasN) {
                    float[] n = readFloats(accessors, views, bin, (int) Json.num(attrs.get("NORMAL")), 3);
                    for (int i = 0; i < n.length; i += 3) mesh.normals.add(new double[] {n[i], n[i + 1], n[i + 2]});
                }
                if (hasT) {
                    float[] t = readFloats(accessors, views, bin, (int) Json.num(attrs.get("TEXCOORD_0")), 2);
                    for (int i = 0; i < t.length; i += 2) mesh.uvs.add(new double[] {t[i], t[i + 1]});
                }
                int[] ind;
                if (prim.containsKey("indices")) ind = readIndices(accessors, views, bin, (int) Json.num(prim.get("indices")));
                else { ind = new int[pos.length / 3]; for (int i = 0; i < ind.length; i++) ind[i] = i; }
                String matName = prim.containsKey("material") ? "MAT" + (int) Json.num(prim.get("material")) : "NONE";
                for (int i = 0; i + 2 < ind.length; i += 3) {
                    Mesh.Tri t = new Mesh.Tri();
                    t.material = matName;
                    t.p0 = vBase + ind[i]; t.p1 = vBase + ind[i + 1]; t.p2 = vBase + ind[i + 2];
                    if (hasN) { t.n0 = nBase + ind[i]; t.n1 = nBase + ind[i + 1]; t.n2 = nBase + ind[i + 2]; }
                    if (hasT) { t.t0 = tBase + ind[i]; t.t1 = tBase + ind[i + 1]; t.t2 = tBase + ind[i + 2]; }
                    mesh.tris.add(t);
                }
            }
        }
        return mesh;
    }

    private static int[] viewInfo(List<Object> accessors, List<Object> views, int acc) {
        Map<String, Object> a = Json.obj(accessors.get(acc));
        Map<String, Object> v = Json.obj(views.get((int) Json.num(a.get("bufferView"))));
        int vo = v.containsKey("byteOffset") ? (int) Json.num(v.get("byteOffset")) : 0;
        int ao = a.containsKey("byteOffset") ? (int) Json.num(a.get("byteOffset")) : 0;
        int stride = v.containsKey("byteStride") ? (int) Json.num(v.get("byteStride")) : 0;
        return new int[] {vo + ao, stride, (int) Json.num(a.get("count")), (int) Json.num(a.get("componentType"))};
    }

    private static float[] readFloats(List<Object> accessors, List<Object> views, ByteBuffer bin, int acc, int comps) {
        int[] info = viewInfo(accessors, views, acc);
        int stride = info[1] != 0 ? info[1] : comps * 4;
        float[] out = new float[info[2] * comps];
        for (int i = 0; i < info[2]; i++)
            for (int c = 0; c < comps; c++) out[i * comps + c] = bin.getFloat(info[0] + i * stride + c * 4);
        return out;
    }

    private static int[] readIndices(List<Object> accessors, List<Object> views, ByteBuffer bin, int acc) {
        int[] info = viewInfo(accessors, views, acc);
        int size = info[3] == 5121 ? 1 : info[3] == 5123 ? 2 : 4;
        int stride = info[1] != 0 ? info[1] : size;
        int[] out = new int[info[2]];
        for (int i = 0; i < info[2]; i++) {
            int p = info[0] + i * stride;
            out[i] = size == 1 ? (bin.get(p) & 255) : size == 2 ? (bin.getShort(p) & 0xFFFF) : bin.getInt(p);
        }
        return out;
    }
}
