package fr.sao.saomatica.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Import .obj (+ .mtl voisin). Port de obj_importer.ts. */
public final class ObjImporter {
    private ObjImporter() {}

    public static Mesh load(Path file) throws IOException {
        Mesh mesh = new Mesh();
        String current = "DEFAULT_UNASSIGNED";
        List<String> mtlFiles = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tk = line.split("\\s+");
            switch (tk[0]) {
                case "mtllib":
                    mtlFiles.add(line.substring(6).trim());
                    break;
                case "usemtl":
                    current = line.substring(6).trim();
                    break;
                case "v":
                    mesh.vertices.add(new double[] {Double.parseDouble(tk[1]), Double.parseDouble(tk[2]), Double.parseDouble(tk[3])});
                    break;
                case "vn":
                    mesh.normals.add(new double[] {Double.parseDouble(tk[1]), Double.parseDouble(tk[2]), Double.parseDouble(tk[3])});
                    break;
                case "vt":
                    mesh.uvs.add(new double[] {Double.parseDouble(tk[1]), tk.length > 2 ? Double.parseDouble(tk[2]) : 0.0});
                    break;
                case "f":
                    face(mesh, tk, current);
                    break;
                default:
                    break;
            }
        }
        Path dir = file.toAbsolutePath().getParent();
        for (String m : mtlFiles) {
            Path p = dir.resolve(m);
            if (Files.exists(p)) loadMtl(mesh, p);
        }
        return mesh;
    }

    private static int idx(String s, int size) {
        int v = Integer.parseInt(s);
        return v < 0 ? size + v : v - 1;
    }

    private static void face(Mesh mesh, String[] tk, String material) {
        int n = tk.length - 1;
        if (n < 3) return;
        int[] p = new int[n], t = new int[n], nn = new int[n];
        for (int i = 0; i < n; i++) {
            String[] d = tk[i + 1].split("/", -1);
            p[i] = idx(d[0], mesh.vertices.size());
            t[i] = d.length > 1 && !d[1].isEmpty() ? idx(d[1], mesh.uvs.size()) : -1;
            nn[i] = d.length > 2 && !d[2].isEmpty() ? idx(d[2], mesh.normals.size()) : -1;
        }
        for (int i = 1; i < n - 1; i++) {
            Mesh.Tri tri = new Mesh.Tri();
            tri.material = material;
            tri.p0 = p[0]; tri.p1 = p[i]; tri.p2 = p[i + 1];
            if (t[0] >= 0 && t[i] >= 0 && t[i + 1] >= 0) { tri.t0 = t[0]; tri.t1 = t[i]; tri.t2 = t[i + 1]; }
            if (nn[0] >= 0 && nn[i] >= 0 && nn[i + 1] >= 0) { tri.n0 = nn[0]; tri.n1 = nn[i]; tri.n2 = nn[i + 1]; }
            mesh.tris.add(tri);
        }
    }

    private static void loadMtl(Mesh mesh, Path mtl) throws IOException {
        Path dir = mtl.toAbsolutePath().getParent();
        Mesh.Material cur = null;
        for (String raw : Files.readAllLines(mtl, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tk = line.split("\\s+");
            if (tk[0].equals("newmtl")) {
                cur = new Mesh.Material();
                mesh.materials.put(line.substring(6).trim(), cur);
            } else if (cur != null && tk[0].equals("Kd") && tk.length >= 4) {
                cur.colour = new double[] {Double.parseDouble(tk[1]), Double.parseDouble(tk[2]), Double.parseDouble(tk[3]), 1.0};
            } else if (cur != null && tk[0].equals("map_Kd")) {
                String name = line.substring(6).trim();
                int sp = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
                Path tex = dir.resolve(sp >= 0 ? name.substring(sp + 1) : name);
                if (Files.exists(tex)) {
                    BufferedImage img = ImageIO.read(tex.toFile());
                    if (img != null) cur.texture = new Texture(img);
                }
            }
        }
    }
}
