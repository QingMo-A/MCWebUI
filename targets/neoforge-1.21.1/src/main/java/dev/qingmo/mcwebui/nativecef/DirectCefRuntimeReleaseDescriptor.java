package dev.qingmo.mcwebui.nativecef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Project-owned metadata for one downloadable runtime artifact.  The
 * descriptor is intentionally separate from runtime.json: runtime.json is
 * supplied by the package, while this object pins the artifact URL, size and
 * checksum in MCWebUI code/release metadata.
 */
public record DirectCefRuntimeReleaseDescriptor(
        int descriptorVersion,
        String artifactId,
        DirectCefRuntimeRequirement requirement,
        String artifactRevision,
        String packageFileName,
        long packageSize,
        long runtimePayloadSize,
        String packageSha256,
        URI downloadUri) {

    public static final int CURRENT_VERSION = 1;

    public DirectCefRuntimeReleaseDescriptor {
        if (descriptorVersion <= 0) throw invalid("descriptorVersion must be positive");
        artifactId = required(artifactId, "artifactId");
        if (!artifactId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw invalid("artifactId contains unsafe characters");
        }
        requirement = Objects.requireNonNull(requirement, "requirement");
        artifactRevision = required(artifactRevision, "artifactRevision");
        if (!artifactRevision.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw invalid("artifactRevision contains unsafe characters");
        }
        packageFileName = required(packageFileName, "packageFileName");
        if (packageFileName.contains("/") || packageFileName.contains("\\")
                || packageFileName.contains("..")) {
            throw invalid("packageFileName must be a single safe file name");
        }
        if (packageSize <= 0) throw invalid("packageSize must be positive");
        if (runtimePayloadSize < 0) throw invalid("runtimePayloadSize must not be negative");
        packageSha256 = required(packageSha256, "packageSha256").toLowerCase(Locale.ROOT);
        if (!packageSha256.matches("[0-9a-f]{64}")) throw invalid("packageSha256 must be 64 hexadecimal characters");
        if (downloadUri != null) validateUri(downloadUri);
    }

    /**
     * Compatibility constructor for Phase C descriptors created before the
     * optional unpacked payload size was introduced. A zero payload size is
     * accepted and causes the downloader to use a conservative package-size
     * fallback for its advisory disk-space estimate.
     */
    public DirectCefRuntimeReleaseDescriptor(int descriptorVersion, String artifactId,
                                             DirectCefRuntimeRequirement requirement,
                                             String artifactRevision, String packageFileName,
                                             long packageSize, String packageSha256, URI downloadUri) {
        this(descriptorVersion, artifactId, requirement, artifactRevision, packageFileName,
                packageSize, 0L, packageSha256, downloadUri);
    }

    public boolean sourceConfigured() { return downloadUri != null; }

    public void validateFor(DirectCefRuntimeRequirement expected) {
        if (expected == null || !requirement.sameIdentity(expected)) {
            throw invalid("release descriptor runtime identity does not match the required Direct CEF runtime");
        }
        if (descriptorVersion != CURRENT_VERSION) throw invalid("unsupported release descriptor version: " + descriptorVersion);
        if (!sourceConfigured()) throw invalid("release descriptor does not configure a download URI");
    }

    public static DirectCefRuntimeReleaseDescriptor parse(Path path) {
        if (path == null) throw invalid("descriptor path must not be null");
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID,
                    "unable to read release descriptor: " + path, ex, path);
        }
    }

    public static DirectCefRuntimeReleaseDescriptor parse(String source) {
        try {
            Object value = Json.parse(source);
            Map<String, Object> root = object(value, "root");
            int version = integer(root, "descriptorVersion");
            String artifactId = string(root, "artifactId");
            String revision = string(root, "artifactRevision");
            String fileName = string(root, "packageFileName", "fileName");
            long size = longValue(root, "packageSize", "size");
            long payloadSize = optionalLongValue(root, "runtimePayloadSize", "unpackedSize");
            String sha = string(root, "packageSha256", "sha256");
            String uriText = optionalString(root, "downloadUri");
            URI uri = uriText == null || uriText.isBlank() ? null : URI.create(uriText);
            DirectCefRuntimeRequirement req = parseRequirement(root);
            return new DirectCefRuntimeReleaseDescriptor(version, artifactId, req, revision,
                    fileName, size, payloadSize, sha, uri);
        } catch (DirectCefRuntimeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID,
                    "invalid Direct CEF release descriptor", ex);
        }
    }

    private static DirectCefRuntimeRequirement parseRequirement(Map<String, Object> root) {
        Object nested = root.get("runtime");
        Map<String, Object> values = nested == null ? root : object(nested, "runtime");
        int schema = integer(values, "schemaVersion");
        int abi = integer(values, "mcwebuiRuntimeAbi", "runtimeAbi");
        return new DirectCefRuntimeRequirement(schema, abi,
                string(values, "runtimeId"), string(values, "cefVersion"),
                string(values, "chromiumVersion"), string(values, "platform"), string(values, "arch"));
    }

    private static void validateUri(URI uri) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw invalid("downloadUri must be an HTTPS URI without userinfo or fragment");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw invalid(name + " must not be blank");
        return value.trim();
    }

    private static DirectCefRuntimeException invalid(String message) {
        return new DirectCefRuntimeException(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, message);
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("descriptor field '" + name + "' must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid("descriptor object key is not a string");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String string(Map<String, Object> object, String... names) {
        for (String name : names) {
            Object value = object.get(name);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        throw invalid("descriptor is missing '" + names[0] + "'");
    }

    private static String optionalString(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value == null) return null;
        if (!(value instanceof String text)) throw invalid("descriptor field '" + name + "' must be a string");
        return text.trim();
    }

    private static int integer(Map<String, Object> object, String... names) {
        long value = longValue(object, names);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid("descriptor integer out of range");
        return (int) value;
    }

    private static long longValue(Map<String, Object> object, String... names) {
        for (String name : names) {
            Object value = object.get(name);
            if (value instanceof Long number) return number;
        }
        throw invalid("descriptor is missing '" + names[0] + "'");
    }

    private static long optionalLongValue(Map<String, Object> object, String... names) {
        for (String name : names) {
            Object value = object.get(name);
            if (value == null) continue;
            if (value instanceof Long number) return number;
            throw invalid("descriptor field '" + name + "' must be an integer");
        }
        return 0L;
    }

    /** Small strict JSON parser copied in spirit from runtime.json parser; no new dependency. */
    static final class Json {
        static Object parse(String source) {
            if (source == null) throw invalid("descriptor JSON must not be null");
            Parser parser = new Parser(source);
            Object value = parser.value();
            parser.ws();
            if (!parser.end()) throw invalid("trailing descriptor JSON content");
            return value;
        }
        private static final class Parser {
            final String source; int index;
            Parser(String source) { this.source = source; }
            boolean end() { return index >= source.length(); }
            void ws() { while (!end() && Character.isWhitespace(source.charAt(index))) index++; }
            char take() { if (end()) throw invalid("unexpected end of descriptor JSON"); return source.charAt(index++); }
            Object value() {
                ws(); if (end()) throw invalid("missing descriptor JSON value");
                return switch (source.charAt(index)) { case '{' -> object(); case '[' -> array(); case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE); case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null); default -> number(); };
            }
            Object literal(String token, Object value) { if (!source.startsWith(token, index)) throw invalid("invalid descriptor literal"); index += token.length(); return value; }
            Map<String,Object> object() { take(); Map<String,Object> out = new LinkedHashMap<>(); ws(); if (!end() && source.charAt(index)=='}'){index++;return out;} while(true){ ws(); String key=string(); if(out.containsKey(key)) throw invalid("duplicate descriptor key: "+key); ws(); if(take()!=':') throw invalid("expected ':'"); out.put(key,value()); ws(); char sep=take(); if(sep=='}') return out; if(sep!=',') throw invalid("expected ','"); } }
            List<Object> array() { take(); List<Object> out=new ArrayList<>(); ws(); if(!end()&&source.charAt(index)==']'){index++;return out;} while(true){out.add(value());ws();char sep=take();if(sep==']')return out;if(sep!=',')throw invalid("expected ','");} }
            String string() { if(take()!='"')throw invalid("expected string"); StringBuilder out=new StringBuilder(); while(!end()){char c=take();if(c=='"')return out.toString();if(c=='\\'){char e=take();switch(e){case '"','\\','/'->out.append(e);case 'b'->out.append('\b');case 'f'->out.append('\f');case 'n'->out.append('\n');case 'r'->out.append('\r');case 't'->out.append('\t');case 'u'->{if(index+4>source.length())throw invalid("bad unicode escape");out.append((char)Integer.parseInt(source.substring(index,index+4),16));index+=4;}default->throw invalid("bad string escape");}}else{if(c<0x20)throw invalid("control character");out.append(c);}}throw invalid("unterminated string"); }
            Number number() { int start=index; if(!end()&&source.charAt(index)=='-')index++; if(end()||!Character.isDigit(source.charAt(index)))throw invalid("invalid number"); if(source.charAt(index)=='0')index++;else while(!end()&&Character.isDigit(source.charAt(index)))index++; if(!end()&&(source.charAt(index)=='.'||source.charAt(index)=='e'||source.charAt(index)=='E'))throw invalid("descriptor numbers must be integers"); try{return Long.valueOf(source.substring(start,index));}catch(NumberFormatException ex){throw invalid("invalid number");} }
        }
    }
}
