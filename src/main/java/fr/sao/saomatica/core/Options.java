package fr.sao.saomatica.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tous les réglages SAOmatica (mêmes défauts que l'interface web). */
public final class Options {
    // Voxelise
    public int size = 80;
    public char constraintAxis = 'y';
    public boolean fillInterior = true;
    public boolean multisample = true;
    public boolean overlapAverage = true;
    // Assign
    public String palette = "all";
    public String dithering = "ordered"; // ordered | random | off
    public double ditheringMagnitude = 32;
    public String fallable = "replace-falling"; // replace-falling | replace-fallable | do-nothing
    public double colourAccuracy = 5; // resolution = 2^accuracy
    public boolean contextualAveraging = true;
    public double errorWeight = 0.2; // divisé par 10 en interne, comme l'appli web
    public double aoWeight = 18;
    public double normalWeight = 12;
    // Export / formes
    public boolean slabs = true;
    public boolean stairs = true;
    public boolean walls = true;
    public boolean trapdoors = true;
    public boolean fences = true;
    public boolean noFullBlocks = false;
    public double slabT = 8;      // affiché : haut = + de dalles ; interne = 100 - valeur
    public double stairT = 90;    // stairVerticalMax
    public double horizMin = 25;  // stairHorizontalMin
    public double wallT = 30;     // wallVerticalMax

    public Options copy() {
        Options o = new Options();
        o.size = size; o.constraintAxis = constraintAxis; o.fillInterior = fillInterior;
        o.multisample = multisample; o.overlapAverage = overlapAverage; o.palette = palette;
        o.dithering = dithering; o.ditheringMagnitude = ditheringMagnitude; o.fallable = fallable;
        o.colourAccuracy = colourAccuracy; o.contextualAveraging = contextualAveraging;
        o.errorWeight = errorWeight; o.aoWeight = aoWeight; o.normalWeight = normalWeight;
        o.slabs = slabs; o.stairs = stairs; o.walls = walls; o.trapdoors = trapdoors; o.fences = fences;
        o.noFullBlocks = noFullBlocks; o.slabT = slabT; o.stairT = stairT; o.horizMin = horizMin; o.wallT = wallT;
        return o;
    }

    public static final String[] PRESET_NAMES = {"organic", "architectural", "terrain", "vegetation"};

    /** Presets identiques à _applySaoPreset() de l'appli web. */
    public boolean applyPreset(String name) {
        switch (name) {
            case "organic": set(18, 12, true, true, true, true, true, true, 8, 90, 25, 30, false); return true;
            case "architectural": set(12, 8, true, true, true, true, true, true, 4, 80, 35, 45, false); return true;
            case "terrain": set(22, 14, true, true, true, false, false, false, 20, 95, 18, 30, false); return true;
            case "vegetation": set(16, 16, false, true, true, false, false, false, 35, 98, 12, 30, false); return true;
            default: return false;
        }
    }

    private void set(double ao, double normal, boolean fill, boolean sl, boolean st, boolean wa, boolean tr,
                     boolean fe, double slT, double stT, double hMin, double wT, boolean noFull) {
        aoWeight = ao; normalWeight = normal; fillInterior = fill; slabs = sl; stairs = st; walls = wa;
        trapdoors = tr; fences = fe; slabT = slT; stairT = stT; horizMin = hMin; wallT = wT; noFullBlocks = noFull;
    }

    /** Liste clé -> description pour /saomatica config. */
    public Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("size", String.valueOf(size));
        m.put("axis", String.valueOf(constraintAxis));
        m.put("fill", String.valueOf(fillInterior));
        m.put("palette", palette);
        m.put("dithering", dithering);
        m.put("ao", String.valueOf(aoWeight));
        m.put("normal", String.valueOf(normalWeight));
        m.put("slabs", String.valueOf(slabs));
        m.put("stairs", String.valueOf(stairs));
        m.put("walls", String.valueOf(walls));
        m.put("trapdoors", String.valueOf(trapdoors));
        m.put("fences", String.valueOf(fences));
        m.put("nofull", String.valueOf(noFullBlocks));
        m.put("slabt", String.valueOf(slabT));
        m.put("stairt", String.valueOf(stairT));
        m.put("horizmin", String.valueOf(horizMin));
        m.put("wallt", String.valueOf(wallT));
        return m;
    }

    /** @return null si OK, sinon message d'erreur. */
    public String setByKey(String key, String v) {
        try {
            switch (key) {
                case "size": size = Math.max(3, Math.min(380, Integer.parseInt(v))); break;
                case "axis": if (!v.matches("[xyz]")) return "axis = x, y ou z"; constraintAxis = v.charAt(0); break;
                case "fill": fillInterior = bool(v); break;
                case "palette":
                    if (!Palettes.names().contains(v)) return "palette inconnue (" + Palettes.names() + ")";
                    palette = v; break;
                case "dithering": if (!v.matches("ordered|random|off")) return "ordered, random ou off"; dithering = v; break;
                case "ao": aoWeight = Double.parseDouble(v); break;
                case "normal": normalWeight = Double.parseDouble(v); break;
                case "slabs": slabs = bool(v); break;
                case "stairs": stairs = bool(v); break;
                case "walls": walls = bool(v); break;
                case "trapdoors": trapdoors = bool(v); break;
                case "fences": fences = bool(v); break;
                case "nofull": noFullBlocks = bool(v); break;
                case "slabt": slabT = Double.parseDouble(v); break;
                case "stairt": stairT = Double.parseDouble(v); break;
                case "horizmin": horizMin = Double.parseDouble(v); break;
                case "wallt": wallT = Double.parseDouble(v); break;
                default: return "clé inconnue : " + key;
            }
        } catch (NumberFormatException e) {
            return "valeur invalide : " + v;
        }
        return null;
    }

    private static boolean bool(String v) { return v.equals("true") || v.equals("on") || v.equals("1") || v.equals("oui"); }
}
