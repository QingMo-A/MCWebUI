package dev.qingmo.mcwebui.nativecef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates an immutable runtime directory before any native DLL is loaded. */
public final class DirectCefRuntimeValidator {
    private static final HexFormat HEX = HexFormat.of();

    private DirectCefRuntimeValidator() { }

    public static ValidatedDirectCefRuntime validate(Path directory) {
        return validate(directory, DirectCefRuntimeRequirement.required());
    }

    public static ValidatedDirectCefRuntime validate(Path directory, DirectCefRuntimeRequirement requirement) {
        if (directory == null) throw failure(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF runtime directory must not be null", null, null);
        if (requirement == null) throw failure(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF runtime requirement must not be null", null, directory);
        Path root = directory.toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) rejectLink(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
                throw failure(DirectCefRuntimeFailureReason.NOT_FOUND, "Direct CEF runtime directory was not found: " + root, null, root);
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Direct CEF runtime path is not a directory: " + root, null, root);
        }
        Path manifestPath = root.resolve(DirectCefRuntimeManifestParser.MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(DirectCefRuntimeFailureReason.MANIFEST_MISSING,
                    "Direct CEF runtime manifest is missing: " + manifestPath, null, manifestPath);
        }
        rejectLink(manifestPath);
        DirectCefRuntimeManifest manifest = DirectCefRuntimeManifestParser.parse(manifestPath);
        validateIdentity(manifest, requirement);
        validateManifest(manifest);

        final Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to resolve Direct CEF runtime directory: " + root, ex, root);
        }

        Map<String, DirectCefRuntimeManifest.FileEntry> expected = new HashMap<>();
        for (DirectCefRuntimeManifest.FileEntry file : manifest.files()) {
            String safe = safeRelativePath(file.path());
            String key = key(safe);
            if (expected.putIfAbsent(key, file) != null) {
                throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH,
                        "Duplicate Direct CEF runtime file path (case-insensitive): " + file.path(), null, root);
            }
            Path filePath = resolveSafe(root, safe);
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(DirectCefRuntimeFailureReason.MISSING_FILE,
                        "Required Direct CEF runtime file is missing: " + safe, null, filePath);
            }
            rejectLink(filePath);
            ensureWithinRoot(realRoot, filePath);
            long actualSize;
            try {
                actualSize = Files.size(filePath);
            } catch (IOException ex) {
                throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to read file size: " + filePath, ex, filePath);
            }
            if (file.size() < 0 || actualSize != file.size()) {
                throw failure(DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH,
                        "Direct CEF runtime file size mismatch for " + safe + " (expected " + file.size() + ", actual " + actualSize + ")",
                        null, filePath);
            }
            String actualHash = sha256(filePath);
            if (!actualHash.equalsIgnoreCase(file.sha256())) {
                throw failure(DirectCefRuntimeFailureReason.HASH_MISMATCH,
                        "Direct CEF runtime SHA-256 mismatch for " + safe, null, filePath);
            }
        }
        validateEntrypoints(manifest.entrypoints(), expected);
        rejectUnexpectedFiles(root, expected);
        return new ValidatedDirectCefRuntime(root, manifest);
    }

    static String safeRelativePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0)
            throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH, "Runtime manifest contains an empty/NUL path", null, null);
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || path.startsWith("//") || path.indexOf(':') >= 0
                || path.matches("^[A-Za-z]:.*"))
            throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH, "Runtime manifest contains an absolute path: " + raw, null, null);
        String[] components = path.split("/", -1);
        if (components.length == 0) throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH, "Runtime manifest path is empty", null, null);
        StringBuilder normalized = new StringBuilder(path.length());
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH, "Runtime manifest contains an unsafe path: " + raw, null, null);
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(component);
        }
        return normalized.toString();
    }

    private static void validateIdentity(DirectCefRuntimeManifest manifest, DirectCefRuntimeRequirement requirement) {
        if (manifest.schemaVersion() != requirement.schemaVersion())
            throw failure(DirectCefRuntimeFailureReason.UNSUPPORTED_SCHEMA, "Unsupported Direct CEF manifest schema: " + manifest.schemaVersion(), null, null);
        if (manifest.mcwebuiRuntimeAbi() != requirement.mcwebuiRuntimeAbi())
            throw failure(DirectCefRuntimeFailureReason.WRONG_ABI, "Unsupported Direct CEF runtime ABI: " + manifest.mcwebuiRuntimeAbi(), null, null);
        if (!manifest.runtimeId().equalsIgnoreCase(requirement.runtimeId()))
            throw failure(DirectCefRuntimeFailureReason.WRONG_RUNTIME_ID, "Direct CEF runtime ID does not match the required pinned runtime", null, null);
        if (!manifest.cefVersion().equalsIgnoreCase(requirement.cefVersion()))
            throw failure(DirectCefRuntimeFailureReason.WRONG_CEF_VERSION, "Direct CEF version does not match the required pinned runtime", null, null);
        if (!manifest.chromiumVersion().equalsIgnoreCase(requirement.chromiumVersion()))
            throw failure(DirectCefRuntimeFailureReason.WRONG_CHROMIUM_VERSION, "Chromium version does not match the required pinned runtime", null, null);
        if (!manifest.platform().equalsIgnoreCase(requirement.platform()))
            throw failure(DirectCefRuntimeFailureReason.WRONG_PLATFORM, "Direct CEF runtime platform is not supported: " + manifest.platform(), null, null);
        if (!manifest.arch().equalsIgnoreCase(requirement.arch()))
            throw failure(DirectCefRuntimeFailureReason.WRONG_ARCH, "Direct CEF runtime architecture is not supported: " + manifest.arch(), null, null);
    }

    private static void validateManifest(DirectCefRuntimeManifest manifest) {
        if (manifest.files().isEmpty()) throw failure(DirectCefRuntimeFailureReason.MANIFEST_INVALID, "Direct CEF runtime manifest has no files", null, null);
        for (DirectCefRuntimeManifest.FileEntry file : manifest.files()) {
            String safe = safeRelativePath(file.path());
            if (key(safe).equals(key(DirectCefRuntimeManifestParser.MANIFEST_FILE_NAME)))
                throw failure(DirectCefRuntimeFailureReason.MANIFEST_INVALID,
                        "The runtime manifest must not list itself as a payload file", null, null);
            if (file.size() < 0) throw failure(DirectCefRuntimeFailureReason.MANIFEST_INVALID, "Runtime file size must not be negative: " + file.path(), null, null);
            if (!file.sha256().matches("(?i)[0-9a-f]{64}"))
                throw failure(DirectCefRuntimeFailureReason.MANIFEST_INVALID, "Runtime file SHA-256 must be 64 hexadecimal characters: " + file.path(), null, null);
        }
    }

    private static void validateEntrypoints(DirectCefRuntimeManifest.EntryPoints entrypoints,
                                             Map<String, DirectCefRuntimeManifest.FileEntry> expected) {
        Set<String> values = new HashSet<>();
        String[] paths = {entrypoints.nativePath(), entrypoints.helperPath(), entrypoints.cefPath(), entrypoints.chromeElfPath()};
        for (String raw : paths) {
            String safe = safeRelativePath(raw);
            if (!values.add(key(safe)))
                throw failure(DirectCefRuntimeFailureReason.INVALID_ENTRYPOINT, "Duplicate Direct CEF runtime entrypoint: " + raw, null, null);
            if (!expected.containsKey(key(safe)))
                throw failure(DirectCefRuntimeFailureReason.INVALID_ENTRYPOINT, "Direct CEF runtime entrypoint is not listed in files: " + raw, null, null);
        }
    }

    private static void rejectUnexpectedFiles(Path root, Map<String, DirectCefRuntimeManifest.FileEntry> expected) {
        Set<String> seen = new HashSet<>();
        try {
            Files.walkFileTree(root, new FileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) rejectLink(dir);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.equals(root.resolve(DirectCefRuntimeManifestParser.MANIFEST_FILE_NAME))) return FileVisitResult.CONTINUE;
                    rejectLink(file);
                    String relative = safeRelativePath(root.relativize(file).toString());
                    String key = key(relative);
                    if (!expected.containsKey(key)) {
                        throw failure(DirectCefRuntimeFailureReason.UNEXPECTED_FILE,
                                "Unexpected file in Direct CEF runtime: " + relative, null, file);
                    }
                    if (!seen.add(key)) throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH,
                            "Duplicate runtime path on disk (case-insensitive): " + relative, null, file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to inspect Direct CEF runtime: " + file, ex, file);
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException ex) {
                    if (ex != null) throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to inspect Direct CEF runtime: " + dir, ex, dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (DirectCefRuntimeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to inspect Direct CEF runtime: " + root, ex, root);
        }
        if (!seen.equals(expected.keySet())) {
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(seen);
            throw failure(DirectCefRuntimeFailureReason.MISSING_FILE, "Required runtime files were not found: " + missing, null, root);
        }
    }

    private static Path resolveSafe(Path root, String relative) {
        Path result = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(root)) throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH, "Runtime path escapes its directory: " + relative, null, result);
        return result;
    }

    private static void ensureWithinRoot(Path realRoot, Path path) {
        try {
            if (!path.toRealPath().startsWith(realRoot))
                throw failure(DirectCefRuntimeFailureReason.SYMLINK_NOT_ALLOWED, "Runtime file escapes its directory: " + path, null, path);
        } catch (IOException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to resolve runtime file: " + path, ex, path);
        }
    }

    private static void rejectLink(Path path) {
        try {
            if (Files.isSymbolicLink(path))
                throw failure(DirectCefRuntimeFailureReason.SYMLINK_NOT_ALLOWED, "Symbolic links are not allowed in Direct CEF runtimes: " + path, null, path);
            try {
                Object reparse = Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS);
                if (Boolean.TRUE.equals(reparse)) throw failure(DirectCefRuntimeFailureReason.SYMLINK_NOT_ALLOWED,
                        "Reparse points are not allowed in Direct CEF runtimes: " + path, null, path);
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                // Non-Windows filesystems do not expose DOS/reparse metadata; the explicit
                // symbolic-link and toRealPath checks still protect the extraction root.
            }
        } catch (IOException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to inspect runtime path: " + path, ex, path);
        }
    }

    private static String sha256(Path path) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR, "Unable to hash runtime file: " + path, ex, path);
        }
        return HEX.formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private static String key(String path) { return path.toLowerCase(Locale.ROOT); }

    private static DirectCefRuntimeException failure(DirectCefRuntimeFailureReason reason, String message,
                                                     Throwable cause, Path path) {
        return new DirectCefRuntimeException(reason, message, cause, path);
    }
}
