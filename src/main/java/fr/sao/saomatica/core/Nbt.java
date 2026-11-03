package fr.sao.saomatica.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Écriture NBT minimale (big-endian, gzip). Valeurs : Byte, Short, Integer, Long, String, byte[], long[], int[], Map (compound), List (liste typée via {@link NList}). */
public final class Nbt {
    private Nbt() {}

    /** Liste NBT typée (type d'élément explicite, nécessaire pour les listes vides). */
    public static final class NList {
        public final int type;
        public final List<?> items;
        public NList(int type, List<?> items) { this.type = type; this.items = items; }
    }

    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, STRING = 8, LIST = 9, COMPOUND = 10, BYTE_ARRAY = 7, INT_ARRAY = 11, LONG_ARRAY = 12;

    public static byte[] writeGzip(String rootName, Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
            out.writeByte(COMPOUND);
            out.writeUTF(rootName);
            payload(out, root);
        }
        return bos.toByteArray();
    }

    private static int typeOf(Object v) {
        if (v instanceof Byte) return BYTE;
        if (v instanceof Short) return SHORT;
        if (v instanceof Integer) return INT;
        if (v instanceof Long) return LONG;
        if (v instanceof String) return STRING;
        if (v instanceof byte[]) return BYTE_ARRAY;
        if (v instanceof int[]) return INT_ARRAY;
        if (v instanceof long[]) return LONG_ARRAY;
        if (v instanceof NList) return LIST;
        if (v instanceof Map) return COMPOUND;
        throw new IllegalArgumentException("Type NBT non géré : " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (typeOf(v)) {
            case BYTE: out.writeByte((Byte) v); break;
            case SHORT: out.writeShort((Short) v); break;
            case INT: out.writeInt((Integer) v); break;
            case LONG: out.writeLong((Long) v); break;
            case STRING: out.writeUTF((String) v); break;
            case BYTE_ARRAY: { byte[] a = (byte[]) v; out.writeInt(a.length); out.write(a); break; }
            case INT_ARRAY: { int[] a = (int[]) v; out.writeInt(a.length); for (int i : a) out.writeInt(i); break; }
            case LONG_ARRAY: { long[] a = (long[]) v; out.writeInt(a.length); for (long l : a) out.writeLong(l); break; }
            case LIST: {
                NList l = (NList) v;
                out.writeByte(l.items.isEmpty() ? END : l.type);
                out.writeInt(l.items.size());
                for (Object o : l.items) payload(out, o);
                break;
            }
            case COMPOUND: {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(END);
                break;
            }
            default: throw new IllegalStateException();
        }
    }
}
