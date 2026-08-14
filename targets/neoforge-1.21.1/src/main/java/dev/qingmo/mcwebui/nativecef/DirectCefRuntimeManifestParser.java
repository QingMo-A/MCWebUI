package dev.qingmo.mcwebui.nativecef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, dependency-free parser for the small runtime.json schema. */
public final class DirectCefRuntimeManifestParser {
    public static final String MANIFEST_FILE_NAME = "runtime.json";

    private DirectCefRuntimeManifestParser() { }

    public static DirectCefRuntimeManifest parse(Path manifest) {
        if (manifest == null) throw invalid("Manifest path must not be null", null);
        final String source;
        try {
            source = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.MANIFEST_INVALID,
                    "Unable to read Direct CEF manifest: " + manifest, ex, manifest);
        }
        try {
            return parseJson(source);
        } catch (DirectCefRuntimeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid("Invalid Direct CEF manifest JSON: " + manifest, ex);
        }
    }

    public static DirectCefRuntimeManifest parse(String source) {
        try {
            return parseJson(source);
        } catch (DirectCefRuntimeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid("Invalid Direct CEF manifest JSON", ex);
        }
    }

    private static DirectCefRuntimeManifest parseJson(String source) {
        Object value = Json.parse(source);
        Map<String, Object> root = object(value, "root");
        int schemaVersion = integer(root, "schemaVersion");
        int abi = integer(root, "mcwebuiRuntimeAbi");
        String runtimeId = string(root, "runtimeId");
        String cefVersion = string(root, "cefVersion");
        String chromiumVersion = string(root, "chromiumVersion");
        String platform = string(root, "platform");
        String arch = string(root, "arch");
        Map<String, Object> endpointObject = object(root.get("entrypoints"), "entrypoints");
        String nativePath = endpoint(endpointObject, "native");
        String helperPath = endpoint(endpointObject, "helper");
        String cefPath = endpoint(endpointObject, "cef");
        String chromeElfPath = endpoint(endpointObject, "chromeElf");
        Object filesValue = root.get("files");
        if (!(filesValue instanceof List<?> values)) throw invalid("Manifest field 'files' must be an array", null);
        ArrayList<DirectCefRuntimeManifest.FileEntry> files = new ArrayList<>(values.size());
        for (Object item : values) {
            Map<String, Object> file = object(item, "files[]");
            String path = string(file, "path");
            long size = longValue(file, "size");
            String sha256 = string(file, "sha256");
            files.add(new DirectCefRuntimeManifest.FileEntry(path, size, sha256));
        }
        try {
            return new DirectCefRuntimeManifest(schemaVersion, abi, runtimeId, cefVersion,
                    chromiumVersion, platform, arch,
                    new DirectCefRuntimeManifest.EntryPoints(nativePath, helperPath, cefPath, chromeElfPath), files);
        } catch (RuntimeException ex) {
            throw invalid("Invalid Direct CEF manifest fields", ex);
        }
    }

    private static String endpoint(Map<String, Object> object, String... names) {
        for (String name : names) {
            if (object.containsKey(name)) return string(object, name);
        }
        throw invalid("Manifest entrypoints is missing '" + names[0] + "'", null);
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Manifest field '" + name + "' must be an object", null);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid("Manifest object key is not a string", null);
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String string(Map<String, Object> object, String name) {
        return string(object.get(name), name);
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid("Manifest field '" + name + "' must be a non-empty string", null);
        return text;
    }

    private static int integer(Map<String, Object> object, String name) {
        long value = longValue(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid("Manifest field '" + name + "' is out of range", null);
        return (int) value;
    }

    private static long longValue(Map<String, Object> object, String name) {
        return longValue(object.get(name), name);
    }

    private static long longValue(Object value, String name) {
        if (!(value instanceof Long number)) {
            throw invalid("Manifest field '" + name + "' must be an integer", null);
        }
        return number.longValue();
    }

    private static DirectCefRuntimeException invalid(String message, Throwable cause) {
        return new DirectCefRuntimeException(DirectCefRuntimeFailureReason.MANIFEST_INVALID, message, cause);
    }

    /** Minimal JSON parser; unlike a permissive object mapper it rejects duplicate keys. */
    private static final class Json {
        static Object parse(String source) {
            if (source == null) throw new IllegalArgumentException("JSON must not be null");
            Parser parser = new Parser(source);
            Object result = parser.value();
            parser.ws();
            if (!parser.end()) throw new IllegalArgumentException("Trailing JSON content");
            return result;
        }

        private static final class Parser {
            final String source;
            int index;
            Parser(String source) { this.source = source; }
            boolean end() { return index >= source.length(); }
            void ws() { while (!end() && Character.isWhitespace(source.charAt(index))) index++; }
            char take() { if (end()) throw new IllegalArgumentException("Unexpected end of JSON"); return source.charAt(index++); }
            Object value() {
                ws();
                if (end()) throw new IllegalArgumentException("Missing JSON value");
                return switch (source.charAt(index)) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }
            Object literal(String token, Object value) {
                if (!source.startsWith(token, index)) throw new IllegalArgumentException("Invalid JSON literal");
                index += token.length();
                return value;
            }
            Map<String, Object> object() {
                take();
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                ws();
                if (!end() && source.charAt(index) == '}') { index++; return result; }
                while (true) {
                    ws();
                    String key = string();
                    if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key: " + key);
                    ws();
                    if (take() != ':') throw new IllegalArgumentException("Expected ':'");
                    result.put(key, value());
                    ws();
                    char separator = take();
                    if (separator == '}') return result;
                    if (separator != ',') throw new IllegalArgumentException("Expected ','");
                }
            }
            List<Object> array() {
                take();
                ArrayList<Object> result = new ArrayList<>();
                ws();
                if (!end() && source.charAt(index) == ']') { index++; return result; }
                while (true) {
                    result.add(value());
                    ws();
                    char separator = take();
                    if (separator == ']') return result;
                    if (separator != ',') throw new IllegalArgumentException("Expected ','");
                }
            }
            String string() {
                if (take() != '"') throw new IllegalArgumentException("Expected string");
                StringBuilder result = new StringBuilder();
                while (!end()) {
                    char c = take();
                    if (c == '"') return result.toString();
                    if (c == '\\') {
                        char escaped = take();
                        switch (escaped) {
                            case '"', '\\', '/' -> result.append(escaped);
                            case 'b' -> result.append('\b');
                            case 'f' -> result.append('\f');
                            case 'n' -> result.append('\n');
                            case 'r' -> result.append('\r');
                            case 't' -> result.append('\t');
                            case 'u' -> {
                                if (index + 4 > source.length()) throw new IllegalArgumentException("Invalid unicode escape");
                                result.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                                index += 4;
                            }
                            default -> throw new IllegalArgumentException("Invalid string escape");
                        }
                    } else {
                        if (c < 0x20) throw new IllegalArgumentException("Control character in string");
                        result.append(c);
                    }
                }
                throw new IllegalArgumentException("Unterminated string");
            }
            Number number() {
                int start = index;
                if (!end() && source.charAt(index) == '-') index++;
                if (end() || !Character.isDigit(source.charAt(index))) throw new IllegalArgumentException("Invalid number");
                if (source.charAt(index) == '0') index++;
                else while (!end() && Character.isDigit(source.charAt(index))) index++;
                boolean decimal = false;
                if (!end() && source.charAt(index) == '.') {
                    decimal = true; index++;
                    if (end() || !Character.isDigit(source.charAt(index))) throw new IllegalArgumentException("Invalid number");
                    while (!end() && Character.isDigit(source.charAt(index))) index++;
                }
                if (!end() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                    decimal = true; index++;
                    if (!end() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                    if (end() || !Character.isDigit(source.charAt(index))) throw new IllegalArgumentException("Invalid number");
                    while (!end() && Character.isDigit(source.charAt(index))) index++;
                }
                String text = source.substring(start, index);
                try {
                    if (decimal) return Double.valueOf(text);
                    return Long.valueOf(text);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid number", ex);
                }
            }
        }
    }
}
