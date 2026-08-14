package dev.qingmo.mcwebui.nativecef;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Deterministic raw ZIP builder for importer tests: stored entries, fixed DOS
 * timestamp, sorted order, exact size/CRC control so corruption cases can be
 * crafted byte-precisely. Two builds of the same input are byte-identical.
 */
final class TestRuntimePackages {
    private TestRuntimePackages() { }

    static final String NATIVE = "mcwebui-direct-cef.dll";
    static final String HELPER = "mcwebui-cef-helper.exe";
    static final String CEF = "libcef.dll";
    static final String ELF = "chrome_elf.dll";

    /**
     * One archive entry. {@code declaredSize} is what the ZIP headers claim;
     * {@code crcDataLength} is how many leading data bytes the CRC covers.
     */
    record Entry(String name, byte[] data, long declaredSize, int crcDataLength) {
        Entry(String name, byte[] data) { this(name, data, data.length, data.length); }
        Entry withDeclaredSize(long size) { return new Entry(name, data, size, crcDataLength); }
        Entry withCrcDataLength(int length) { return new Entry(name, data, declaredSize, length); }
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    /** The four entrypoint files plus any extra paths (e.g. locales/en-US.pak). */
    static Map<String, byte[]> runtimeFiles(Map<String, byte[]> extra) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(NATIVE, utf8("jni"));
        files.put(HELPER, utf8("helper"));
        files.put(CEF, utf8("cef"));
        files.put(ELF, utf8("elf"));
        if (extra != null) files.putAll(extra);
        return files;
    }

    static String manifestJson(Map<String, byte[]> files) {
        List<String> names = new ArrayList<>(files.keySet());
        names.sort(String::compareTo);
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) entries.append(',');
            byte[] bytes = files.get(names.get(i));
            entries.append("{\"path\":\"").append(names.get(i)).append("\",\"size\":").append(bytes.length)
                    .append(",\"sha256\":\"").append(sha256(bytes)).append("\"}");
        }
        return "{\"schemaVersion\":1,\"mcwebuiRuntimeAbi\":1,\"runtimeId\":\""
                + DirectCefRuntimeRequirement.RUNTIME_ID + "\",\"cefVersion\":\""
                + DirectCefRuntimeRequirement.CEF_VERSION + "\",\"chromiumVersion\":\""
                + DirectCefRuntimeRequirement.CHROMIUM_VERSION + "\",\"platform\":\"windows\",\"arch\":\"x86_64\","
                + "\"entrypoints\":{\"native\":\"" + NATIVE + "\",\"helper\":\"" + HELPER
                + "\",\"cef\":\"" + CEF + "\",\"chromeElf\":\"" + ELF + "\"},\"files\":[" + entries + "]}";
    }

    static DirectCefRuntimeManifest parseManifest(String json) {
        return DirectCefRuntimeManifestParser.parse(json);
    }

    static byte[] packageZip(Map<String, byte[]> files) {
        return packageZip(files, manifestJson(files));
    }

    static byte[] packageZip(Map<String, byte[]> files, String manifestJson) {
        return zip(entries(files, manifestJson));
    }

    static List<Entry> entries(Map<String, byte[]> files, String manifestJson) {
        List<String> names = new ArrayList<>(files.keySet());
        names.sort(String::compareTo);
        List<Entry> entries = new ArrayList<>();
        for (String name : names) entries.add(new Entry(name, files.get(name)));
        entries.add(new Entry("runtime.json", utf8(manifestJson)));
        return entries;
    }

    static byte[] zip(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> centralRecords = new ArrayList<>();
        int offset = 0;
        for (Entry entry : entries) {
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.data(), 0, entry.crcDataLength());
            ByteBuffer header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0x21)
                    .putInt((int) crc.getValue())
                    .putInt((int) entry.declaredSize()).putInt((int) entry.declaredSize())
                    .putShort((short) nameBytes.length).putShort((short) 0);
            out.writeBytes(header.array());
            out.writeBytes(nameBytes);
            out.writeBytes(entry.data());
            ByteBuffer central = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
            central.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0x21)
                    .putInt((int) crc.getValue())
                    .putInt((int) entry.declaredSize()).putInt((int) entry.declaredSize())
                    .putShort((short) nameBytes.length).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset);
            centralRecords.add(central.array());
            offset += 30 + nameBytes.length + entry.data().length;
        }
        int centralStart = out.size();
        for (int i = 0; i < entries.size(); i++) {
            out.writeBytes(centralRecords.get(i));
            out.writeBytes(entries.get(i).name().getBytes(StandardCharsets.UTF_8));
        }
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) entries.size()).putShort((short) entries.size())
                .putInt(out.size() - centralStart).putInt(centralStart).putShort((short) 0);
        out.writeBytes(eocd.array());
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
