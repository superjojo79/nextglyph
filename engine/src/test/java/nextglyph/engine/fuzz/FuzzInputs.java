package nextglyph.engine.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Outils partagés des tests de fuzzing : entrées brutes ou dérivées d'un fichier valide. */
final class FuzzInputs {

    private static final int MAX_PATCHES = 8;

    private FuzzInputs() {
    }

    /**
     * Soit les octets bruts du fuzzer, soit {@code valid} altéré en quelques endroits (et parfois tronqué ou
     * allongé) : le second cas atteint beaucoup plus vite les contrôles en profondeur que des octets aléatoires.
     */
    static byte[] bytes(FuzzedDataProvider data, byte[] valid) {
        if (data.consumeBoolean()) {
            return data.consumeRemainingAsBytes();
        }
        byte[] copy = valid.clone();
        int patches = data.consumeInt(0, MAX_PATCHES);
        for (int i = 0; i < patches && copy.length > 0; i++) {
            copy[data.consumeInt(0, copy.length - 1)] = data.consumeByte();
        }
        if (data.consumeBoolean()) {
            int length = data.consumeInt(0, copy.length + MAX_PATCHES);
            byte[] resized = new byte[length];
            System.arraycopy(copy, 0, resized, 0, Math.min(length, copy.length));
            copy = resized;
        }
        return copy;
    }

    /** Version texte de {@link #bytes} pour les formats stockés en chaîne. */
    static String text(FuzzedDataProvider data, String valid) {
        if (data.consumeBoolean()) {
            return data.consumeRemainingAsString();
        }
        byte[] patched = bytes(data, valid.getBytes(StandardCharsets.UTF_8));
        return new String(patched, StandardCharsets.UTF_8);
    }

    /** Recalcule les sommes de contrôle des sections d'un fichier d'état tant que sa structure le permet. */
    static void fixChecksums(byte[] file) {
        final int header = 7;
        final int sectionHeader = 6;
        if (file.length < header) {
            return;
        }
        int count = file[header - 1] & 0xFF;
        int position = header;
        for (int i = 0; i < count; i++) {
            if (file.length - position < sectionHeader) {
                return;
            }
            long length = u32(file, position + 2);
            long end = position + sectionHeader + length;
            if (end + 4 > file.length) {
                return;
            }
            CRC32 crc = new CRC32();
            crc.update(file, position, (int) (end - position));
            long value = crc.getValue();
            for (int b = 0; b < 4; b++) {
                file[(int) end + b] = (byte) (value >>> (24 - 8 * b));
            }
            position = (int) end + 4;
        }
    }

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }
}
