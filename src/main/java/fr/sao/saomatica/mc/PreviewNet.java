package fr.sao.saomatica.mc;

import fr.sao.saomatica.core.BlockGrid;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Paquets serveur -> client pour l'aperçu fantôme. */
public final class PreviewNet {
    private static final int CHUNK = 400_000;

    private PreviewNet() {}

    /** Un morceau de la grille compressée. */
    public record Chunk(int sx, int sy, int sz, int index, int total, byte[] data) implements CustomPayload {
        public static final Id<Chunk> ID = new Id<>(Identifier.of("saomatica", "preview_chunk"));
        public static final PacketCodec<RegistryByteBuf, Chunk> CODEC = PacketCodec.of(
            (v, buf) -> { buf.writeVarInt(v.sx); buf.writeVarInt(v.sy); buf.writeVarInt(v.sz); buf.writeVarInt(v.index); buf.writeVarInt(v.total); buf.writeByteArray(v.data); },
            buf -> new Chunk(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** action 0 = effacer l'aperçu, 1 = nouvelle origine (x,y,z). */
    public record Ctl(int action, int x, int y, int z) implements CustomPayload {
        public static final Id<Ctl> ID = new Id<>(Identifier.of("saomatica", "preview_ctl"));
        public static final PacketCodec<RegistryByteBuf, Ctl> CODEC = PacketCodec.of(
            (v, buf) -> { buf.writeVarInt(v.action); buf.writeInt(v.x); buf.writeInt(v.y); buf.writeInt(v.z); },
            buf -> new Ctl(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Chunk.ID, Chunk.CODEC);
        PayloadTypeRegistry.playS2C().register(Ctl.ID, Ctl.CODEC);
    }

    public static void sendOrigin(ServerPlayerEntity p, BlockPos o) { ServerPlayNetworking.send(p, new Ctl(1, o.getX(), o.getY(), o.getZ())); }

    public static void sendClear(ServerPlayerEntity p) { ServerPlayNetworking.send(p, new Ctl(0, 0, 0, 0)); }

    public static void send(ServerPlayerEntity p, BlockGrid g, BlockPos origin) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        var pal = g.palette();
        varint(out, pal.size());
        for (String s : pal) out.writeUTF(s);
        int i = 0, n = g.data.length;
        while (i < n) {
            int v = g.data[i], j = i + 1;
            while (j < n && g.data[j] == v) j++;
            varint(out, j - i);
            varint(out, v);
            i = j;
        }
        out.flush();
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw.toByteArray());
        d.finish();
        ByteArrayOutputStream comp = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) comp.write(buf, 0, d.deflate(buf));
        d.end();
        byte[] all = comp.toByteArray();
        int total = Math.max(1, (all.length + CHUNK - 1) / CHUNK);
        for (int k = 0; k < total; k++) {
            int from = k * CHUNK, to = Math.min(all.length, from + CHUNK);
            byte[] part = new byte[to - from];
            System.arraycopy(all, from, part, 0, part.length);
            ServerPlayNetworking.send(p, new Chunk(g.sx, g.sy, g.sz, k, total, part));
        }
        sendOrigin(p, origin);
    }

    private static void varint(DataOutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) { o.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        o.writeByte(v);
    }
}
