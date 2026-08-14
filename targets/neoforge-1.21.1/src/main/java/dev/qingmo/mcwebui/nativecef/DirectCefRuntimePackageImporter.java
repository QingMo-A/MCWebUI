package dev.qingmo.mcwebui.nativecef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Offline Direct CEF runtime package importer (Phase B).
 *
 * <p>Pure-Java filesystem/runtime distribution logic: it does not depend on
 * Minecraft, NeoForge, CEF native code, or any rendering API, so it can later
 * be shared with other loaders. The importer only installs; it never creates a
 * browser. A valid import ends with the runtime published at the standard
 * instance location and rediscovered through
 * {@link DirectCefRuntimeDiscovery} exactly like a preinstalled runtime.</p>
 *
 * <p>Flow: optional whole-package SHA-256 -&gt; read {@code runtime.json} from
 * the ZIP first -&gt; identity/ABI/platform/arch checks -&gt; exact entry
 * validation (no extra files, no unsafe paths, no case-insensitive
 * duplicates, no zip bombs) -&gt; process-shared install lock -&gt; re-check
 * the standard runtime -&gt; safe staging extraction (streaming size-bounded
 * writes plus one-pass SHA-256) -&gt; the Phase A validator on the staging
 * directory -&gt; atomic publish (quarantine-based repair with rollback) -&gt;
 * Phase A validation of the published directory -&gt; standard discovery.</p>
 *
 * <p>Integrity versus authenticity: file SHA-256s and an optional package
 * SHA-256 prove exact artifact identity and content consistency, not
 * publisher authenticity. A trusted distribution/signing model is Phase C
 * work; do not describe hashes as trust.</p>
 */
public final class DirectCefRuntimePackageImporter {
    /** Staging directories are siblings of the final runtime, e.g. {@code windows-x86_64.installing-<uuid>}. */
    public static final String STAGING_SUFFIX = ".installing-";
    /** Quarantined invalid runtimes, e.g. {@code windows-x86_64.invalid-<uuid>}. */
    public static final String QUARANTINE_SUFFIX = ".invalid-";
    /** Stable lock directory beside the runtime tree, never inside a final runtime. */
    public static final String LOCK_DIRECTORY_NAME = ".locks";
    public static final long DEFAULT_LOCK_TIMEOUT_MILLIS = 30_000L;
    /** Hard bound for the manifest itself so a hostile runtime.json cannot exhaust memory. */
    public static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;
    /** A single runtime file larger than this is not a CEF 144 runtime. */
    public static final long MAX_SINGLE_FILE_BYTES = 1L << 30;
    /** Total uncompressed payload bound (manifest sizes); CEF 144 is well below this. */
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 4L << 30;

    private static final HexFormat HEX = HexFormat.of();
    private static final String MANIFEST_FILE_NAME = DirectCefRuntimeManifestParser.MANIFEST_FILE_NAME;

    /**
     * Test-only fault injection for publish-failure rollback tests. Production
     * callers must never set this (mirrors {@code DirectCefNativeLoader.resetForTests}).
     */
    @FunctionalInterface
    interface PublishInterceptor {
        void beforePublish(Path staging, Path finalDirectory) throws IOException;
    }

    static volatile PublishInterceptor publishInterceptor;

    private DirectCefRuntimePackageImporter() { }

    /** Cooperative cancellation; checked at file boundaries and inside large streams. */
    public interface CancellationToken {
        boolean isCancelled();
    }

    /** Receives immutable progress snapshots; called from the importing thread. */
    public interface ProgressSink {
        void onProgress(RuntimeImportProgress progress);
    }

    /** Import tuning knobs; all fields optional. */
    public record Options(DirectCefRuntimeRequirement requirement, String expectedPackageSha256,
                          ProgressSink progress, CancellationToken cancellation,
                          long lockTimeoutMillis) {
        public Options {
            requirement = requirement == null ? DirectCefRuntimeRequirement.required() : requirement;
            if (lockTimeoutMillis <= 0) lockTimeoutMillis = DEFAULT_LOCK_TIMEOUT_MILLIS;
        }
    }

    public static RuntimeImportResult importPackage(Path packageFile, Path instanceRoot) {
        return importPackage(packageFile, instanceRoot, new Options(null, null, null, null, DEFAULT_LOCK_TIMEOUT_MILLIS));
    }

    public static RuntimeImportResult importPackage(Path packageFile, Path instanceRoot, Options options) {
        if (options == null) throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF runtime import options must not be null");
        Path packagePath = packageFile == null ? null : packageFile.toAbsolutePath().normalize();
        Path root = instanceRoot == null ? null : instanceRoot.toAbsolutePath().normalize();
        if (packagePath == null) return failedResult(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF runtime package path must not be null", null, root, null, null);
        if (root == null) return failedResult(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF instance root must not be null", null, packagePath, null, null);
        if (!Files.isRegularFile(packagePath, LinkOption.NOFOLLOW_LINKS)) {
            return failedResult(DirectCefRuntimeFailureReason.IO_ERROR,
                    "Runtime package file was not found: " + packagePath, null, packagePath, root, null);
        }
        ProgressSink progress = options.progress() == null ? p -> { } : options.progress();
        CancellationToken cancel = options.cancellation() == null ? () -> false : options.cancellation();
        DirectCefRuntimeRequirement requirement = options.requirement();
        Path finalDirectory = DirectCefRuntimeDiscovery.standardDirectory(root, requirement);
        Path staging = null;
        Path quarantine = null;
        boolean published = false;
        try {
            progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.VALIDATING_PACKAGE,
                    "Validating runtime package"));
            checkCancelled(cancel);
            verifyPackageHash(packagePath, options.expectedPackageSha256());

            PackageContents contents = readPackage(packagePath);
            // Reuse the Phase A identity and manifest-shape gates; no second validation dialect.
            DirectCefRuntimeValidator.validateIdentity(contents.manifest(), requirement);
            DirectCefRuntimeValidator.validateManifest(contents.manifest());
            if (contents.totalExpectedBytes() > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                        "Runtime package declares more than " + MAX_TOTAL_UNCOMPRESSED_BYTES
                                + " uncompressed bytes; refusing a zip-bomb-shaped package", packagePath);
            }
            for (DirectCefRuntimeManifest.FileEntry file : contents.manifest().files()) {
                if (file.size() > MAX_SINGLE_FILE_BYTES) {
                    throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                            "Runtime package file exceeds the single-file limit: " + file.path(), packagePath);
                }
            }

            Path lockFile = lockFilePath(root, requirement);
            try {
                Files.createDirectories(lockFile.getParent());
            } catch (IOException ex) {
                throw failure(DirectCefRuntimeFailureReason.IO_ERROR,
                        "Unable to create runtime lock directory: " + lockFile.getParent(), ex, lockFile.getParent());
            }
            progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.WAITING_FOR_LOCK,
                    "Waiting for the runtime installation lock"));
            checkCancelled(cancel);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = acquireInstallLock(channel, options.lockTimeoutMillis(), cancel);
                if (lock == null) {
                    DirectCefRuntimeException busy = failure(DirectCefRuntimeFailureReason.INSTALL_IN_PROGRESS,
                            "Another process is installing this Direct CEF runtime (lock: " + lockFile + ")", lockFile);
                    return new RuntimeImportResult(RuntimeImportResult.Status.INSTALL_IN_PROGRESS, null, busy,
                            packagePath, null, finalDirectory);
                }
                try (FileLock ignored = lock) {
                    // Re-check under the lock: another instance may have finished while we waited.
                    DirectCefRuntimeDiscovery.Probe existing = DirectCefRuntimeDiscovery.probe(root, (Path) null, requirement);
                    if (existing.valid()) {
                        return new RuntimeImportResult(RuntimeImportResult.Status.ALREADY_INSTALLED,
                                existing.runtime(), null, packagePath, null, finalDirectory);
                    }
                    if (loadedRootMatches(finalDirectory)) {
                        DirectCefRuntimeException inUse = failure(DirectCefRuntimeFailureReason.RUNTIME_IN_USE,
                                "The target runtime is loaded by this process and must not be replaced: "
                                        + finalDirectory, finalDirectory);
                        return new RuntimeImportResult(RuntimeImportResult.Status.FAILED, null, inUse,
                                packagePath, null, finalDirectory);
                    }

                    if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        // The standard directory exists but failed validation: quarantine it before
                        // publishing anything so the final path is never left half-replaced.
                        quarantine = sibling(finalDirectory, QUARANTINE_SUFFIX);
                        moveNoReplace(finalDirectory, quarantine);
                    }

                    staging = sibling(finalDirectory, STAGING_SUFFIX);
                    if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure(DirectCefRuntimeFailureReason.IO_ERROR,
                                "Staging path already exists; refusing to reuse it: " + staging, staging);
                    }
                    try {
                        Files.createDirectories(staging);
                        extractPackage(packagePath, contents, staging, progress, cancel);
                        progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.VALIDATING_RUNTIME,
                                "Validating the extracted runtime"));
                        checkCancelled(cancel);
                        // The Phase A validator is authoritative; the importer never re-implements validation.
                        DirectCefRuntimeValidator.validate(staging, requirement);
                        progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.PUBLISHING,
                                "Publishing the runtime"));
                        checkCancelled(cancel);
                        PublishInterceptor interceptor = publishInterceptor;
                        if (interceptor != null) interceptor.beforePublish(staging, finalDirectory);
                        publish(staging, finalDirectory);
                        published = true;
                        DirectCefRuntimeValidator.validate(finalDirectory, requirement);
                        if (quarantine != null) deleteBestEffort(quarantine);
                        progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.COMPLETE,
                                "Runtime installed"));
                        ValidatedDirectCefRuntime rediscovered =
                                DirectCefRuntimeDiscovery.discover(root, (Path) null, requirement);
                        return new RuntimeImportResult(RuntimeImportResult.Status.INSTALLED, rediscovered, null,
                                packagePath, null, finalDirectory);
                    } catch (RuntimeException | IOException ex) {
                        rollbackAfterFailure(published, staging, finalDirectory, quarantine);
                        if (ex instanceof CancelledException) {
                            progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.CANCELLED,
                                    "Import cancelled"));
                            return new RuntimeImportResult(RuntimeImportResult.Status.CANCELLED, null,
                                    failure(DirectCefRuntimeFailureReason.INSTALL_CANCELLED,
                                            "Runtime import was cancelled", null), packagePath, staging, finalDirectory);
                        }
                        if (ex instanceof DirectCefRuntimeException typed) {
                            DirectCefRuntimeException wrapped = new DirectCefRuntimeException(typed.reason(),
                                    typed.getMessage() + pathDetail(staging, quarantine, finalDirectory),
                                    typed.getCause(), typed.path());
                            progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.FAILED,
                                    wrapped.getMessage()));
                            return new RuntimeImportResult(RuntimeImportResult.Status.FAILED, null, wrapped,
                                    packagePath, staging, finalDirectory);
                        }
                        DirectCefRuntimeException wrapped = failure(DirectCefRuntimeFailureReason.IO_ERROR,
                                "Runtime package import failed: " + ex.getMessage()
                                        + pathDetail(staging, quarantine, finalDirectory), ex, packagePath);
                        progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.FAILED,
                                wrapped.getMessage()));
                        return new RuntimeImportResult(RuntimeImportResult.Status.FAILED, null, wrapped,
                                packagePath, staging, finalDirectory);
                    }
                }
            }
        } catch (DirectCefRuntimeException ex) {
            progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.FAILED, ex.getMessage()));
            return new RuntimeImportResult(RuntimeImportResult.Status.FAILED, null, ex,
                    packagePath, staging, finalDirectory);
        } catch (IOException ex) {
            DirectCefRuntimeException wrapped = failure(DirectCefRuntimeFailureReason.IO_ERROR,
                    "Runtime package import failed: " + ex.getMessage()
                            + pathDetail(staging, quarantine, finalDirectory), ex, packagePath);
            progress.onProgress(RuntimeImportProgress.simple(RuntimeImportProgress.Phase.FAILED, wrapped.getMessage()));
            return new RuntimeImportResult(RuntimeImportResult.Status.FAILED, null, wrapped,
                    packagePath, staging, finalDirectory);
        }
    }

    /** Stable, process-shared lock path for one runtime identity, outside any final runtime. */
    public static Path lockFilePath(Path instanceRoot, DirectCefRuntimeRequirement requirement) {
        Path root = instanceRoot.toAbsolutePath().normalize();
        return root.resolve("mcwebui").resolve("runtime").resolve(LOCK_DIRECTORY_NAME)
                .resolve(requirement.runtimeId() + "-" + requirement.platform() + "-" + requirement.arch() + ".lock");
    }

    private static PackageContents readPackage(Path packageFile) {
        try (ZipFile zip = new ZipFile(packageFile.toFile(), StandardCharsets.UTF_8)) {
            List<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) entries.add(enumeration.nextElement());

            List<ZipEntry> manifestEntries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ZipEntry entry : entries) {
                String key = key(validateEntryPath(entry.getName()));
                if (!seen.add(key)) {
                    throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                            "Runtime package contains case-insensitive duplicate entries: " + entry.getName(), packageFile);
                }
                if (key.equals(key(MANIFEST_FILE_NAME))) manifestEntries.add(entry);
            }
            if (manifestEntries.isEmpty()) {
                throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                        "Runtime package is missing " + MANIFEST_FILE_NAME + " at its root", packageFile);
            }
            if (manifestEntries.size() > 1) {
                throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                        "Runtime package contains more than one " + MANIFEST_FILE_NAME, packageFile);
            }
            ZipEntry manifestEntry = manifestEntries.get(0);
            if (manifestEntry.isDirectory()) {
                throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                        MANIFEST_FILE_NAME + " must be a regular file", packageFile);
            }
            if (manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                        MANIFEST_FILE_NAME + " is unreasonably large", packageFile);
            }
            byte[] manifestBytes = readBounded(zip.getInputStream(manifestEntry), MAX_MANIFEST_BYTES);
            DirectCefRuntimeManifest manifest =
                    DirectCefRuntimeManifestParser.parse(new String(manifestBytes, StandardCharsets.UTF_8));

            Map<String, Long> expectedSizes = new HashMap<>();
            Set<String> expectedFiles = new TreeSet<>();
            for (DirectCefRuntimeManifest.FileEntry file : manifest.files()) {
                String safe = DirectCefRuntimeValidator.safeRelativePath(file.path());
                if (!expectedFiles.add(safe)) {
                    throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                            "Runtime manifest contains a duplicate file path: " + safe, packageFile);
                }
                expectedSizes.put(safe, file.size());
            }

            Set<String> presentFiles = new HashSet<>();
            for (ZipEntry entry : entries) {
                String raw = entry.getName();
                if (key(raw).equals(key(MANIFEST_FILE_NAME))) continue;
                boolean directory = raw.endsWith("/");
                String safe = validateEntryPath(raw);
                if (directory) {
                    if (!isAncestorOfAnyFile(safe, expectedFiles)) {
                        throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                                "Runtime package directory entry contains no files: " + raw, packageFile);
                    }
                } else {
                    if (!expectedFiles.contains(safe)) {
                        throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                                "Runtime package contains an entry that is not listed in runtime.json: " + raw, packageFile);
                    }
                    if (!presentFiles.add(safe)) {
                        throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                                "Runtime package contains a duplicate file entry: " + raw, packageFile);
                    }
                    long declared = entry.getSize();
                    long expected = expectedSizes.get(safe);
                    if (declared >= 0 && declared != expected) {
                        throw failure(DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH,
                                "Runtime package entry size does not match runtime.json for " + safe
                                        + " (declared " + declared + ", expected " + expected + ")", packageFile);
                    }
                }
            }
            for (String expected : expectedFiles) {
                if (!presentFiles.contains(expected)) {
                    throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                            "Runtime package is missing a runtime.json file: " + expected, packageFile);
                }
            }
            long total = 0;
            for (DirectCefRuntimeManifest.FileEntry file : manifest.files()) total += file.size();
            return new PackageContents(manifest, manifestBytes, total);
        } catch (ZipException ex) {
            throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                    "Runtime package is not a readable ZIP archive: " + packageFile, ex, packageFile);
        } catch (IOException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR,
                    "Unable to read runtime package: " + packageFile, ex, packageFile);
        }
    }

    /**
     * Package entry paths use only '/' separated relative components: reject
     * absolute paths, drive qualifiers, UNC/backslash forms, '.', '..', empty
     * components and NUL bytes. Comparison is case-insensitive on Windows.
     */
    private static String validateEntryPath(String rawName) {
        if (rawName == null || rawName.indexOf('\0') >= 0) {
            throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH,
                    "Runtime package contains an invalid entry name", null);
        }
        if (rawName.indexOf('\\') >= 0) {
            throw failure(DirectCefRuntimeFailureReason.UNSAFE_PATH,
                    "Runtime package entry contains a backslash: " + rawName, null);
        }
        boolean directory = rawName.endsWith("/");
        String candidate = directory ? rawName.substring(0, rawName.length() - 1) : rawName;
        return DirectCefRuntimeValidator.safeRelativePath(candidate);
    }

    private static boolean isAncestorOfAnyFile(String candidate, Set<String> expectedFiles) {
        String prefix = candidate + "/";
        for (String file : expectedFiles) {
            if (file.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void extractPackage(Path packageFile, PackageContents contents, Path staging,
                                       ProgressSink progress, CancellationToken cancel) throws IOException {
        DirectCefRuntimeManifest manifest = contents.manifest();
        List<DirectCefRuntimeManifest.FileEntry> files = manifest.files();
        int total = files.size() + 1; // runtime.json is the last extracted entry
        long expectedBytes = contents.totalExpectedBytes() + contents.manifestBytes().length;
        long extractedBytes = 0;
        int completed = 0;
        try (ZipFile zip = new ZipFile(packageFile.toFile(), StandardCharsets.UTF_8)) {
            for (DirectCefRuntimeManifest.FileEntry file : files) {
                String safe = DirectCefRuntimeValidator.safeRelativePath(file.path());
                checkCancelled(cancel);
                progress.onProgress(new RuntimeImportProgress(RuntimeImportProgress.Phase.EXTRACTING,
                        "Extracting " + safe, safe, completed, total, extractedBytes, expectedBytes));
                ZipEntry entry = zip.getEntry(safe);
                if (entry == null) {
                    throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                            "Runtime package entry disappeared while extracting: " + safe, packageFile);
                }
                Path target = staging.resolve(safe.replace('/', java.io.File.separatorChar));
                Files.createDirectories(target.getParent());
                long size = extractOne(zip, entry, target, file.size(), file.sha256(), cancel);
                extractedBytes += size;
                completed++;
            }
            checkCancelled(cancel);
            progress.onProgress(new RuntimeImportProgress(RuntimeImportProgress.Phase.EXTRACTING,
                    "Extracting " + MANIFEST_FILE_NAME, MANIFEST_FILE_NAME, completed, total,
                    extractedBytes, expectedBytes));
            // runtime.json last: a partially extracted staging directory never looks complete.
            Files.write(staging.resolve(MANIFEST_FILE_NAME), contents.manifestBytes());
        }
    }

    /** Streaming extraction: writes at most the manifest size and hashes in the same pass. */
    private static long extractOne(ZipFile zip, ZipEntry entry, Path target, long expectedSize,
                                   String expectedSha, CancellationToken cancel) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
        try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            long sinceCancelCheck = 0;
            while (true) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                written += read;
                if (written > expectedSize) {
                    throw failure(DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH,
                            "Runtime package entry exceeds the runtime.json size for " + entry.getName(), target);
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                sinceCancelCheck += read;
                if (sinceCancelCheck >= 1024 * 1024) {
                    sinceCancelCheck = 0;
                    checkCancelled(cancel);
                }
            }
            if (written != expectedSize) {
                throw failure(DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH,
                        "Runtime package entry ended before the runtime.json size for " + entry.getName()
                                + " (got " + written + ", expected " + expectedSize + ")", target);
            }
            String actual = HEX.formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            if (!actual.equalsIgnoreCase(expectedSha)) {
                throw failure(DirectCefRuntimeFailureReason.HASH_MISMATCH,
                        "Runtime package file SHA-256 does not match runtime.json for " + entry.getName(), target);
            }
            return written;
        } catch (ZipException ex) {
            throw failure(DirectCefRuntimeFailureReason.HASH_MISMATCH,
                    "Runtime package entry failed its ZIP integrity check: " + entry.getName(), ex, target);
        }
    }

    private static void publish(Path staging, Path finalDirectory) throws IOException {
        try {
            Files.move(staging, finalDirectory, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            // Staging is a sibling of the final directory, so a plain same-filesystem
            // move is equivalent. The published directory is validated immediately after.
            Files.move(staging, finalDirectory);
        }
    }

    private static void moveNoReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException ex) {
            throw failure(DirectCefRuntimeFailureReason.IO_ERROR,
                    "Refusing to overwrite an existing path: " + target, ex, target);
        }
    }

    /**
     * Best-effort restore after a failed publish. Never deletes the old runtime
     * before the new one is validated; if rollback also fails, the error paths
     * let the user recover manually.
     */
    private static void rollbackAfterFailure(boolean published, Path staging, Path finalDirectory, Path quarantine) {
        if (published && finalDirectory != null && Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)
                && staging != null && !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.move(finalDirectory, staging);
            } catch (IOException ignored) {
                // The error message below still names every path.
            }
        }
        if (quarantine != null && Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)
                && (finalDirectory == null || !Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS))) {
            try {
                Files.move(quarantine, finalDirectory);
            } catch (IOException ignored) {
                // The error message below still names every path.
            }
        }
        if (staging != null) deleteBestEffort(staging);
    }

    private static void deleteBestEffort(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) { }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException ex) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException ex) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) { }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
    }

    private static FileLock acquireInstallLock(FileChannel channel, long timeoutMillis,
                                               CancellationToken cancel) throws IOException {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        while (true) {
            checkCancelled(cancel);
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ex) {
                // Held by this JVM.
            } catch (IOException ex) {
                // Windows reports a cross-process overlap as an IOException instead of
                // returning null; treat any lock attempt failure as busy for this round.
            }
            if (System.nanoTime() >= deadline) return null;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }

    private static void verifyPackageHash(Path packageFile, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) return;
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw failure(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                    "Expected package SHA-256 must be 64 hexadecimal characters", packageFile);
        }
        String actual = sha256File(packageFile);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw failure(DirectCefRuntimeFailureReason.PACKAGE_HASH_MISMATCH,
                    "Runtime package SHA-256 mismatch (expected " + expectedSha256.toLowerCase(Locale.ROOT)
                            + ", actual " + actual + ")", packageFile);
        }
    }

    private static String sha256File(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read == 0) continue;
            total += read;
            if (total > maxBytes) {
                throw failure(DirectCefRuntimeFailureReason.PACKAGE_INVALID,
                        "Runtime package manifest exceeds the maximum size", null);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean loadedRootMatches(Path finalDirectory) {
        Path loaded = DirectCefNativeLoader.loadedRoot();
        if (loaded == null) return false;
        String left = loaded.toAbsolutePath().normalize().toString();
        String right = finalDirectory.toAbsolutePath().normalize().toString();
        return windows() ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path sibling(Path directory, String suffix) {
        return directory.resolveSibling(directory.getFileName() + suffix + UUID.randomUUID());
    }

    private static void checkCancelled(CancellationToken cancel) {
        if (cancel.isCancelled()) throw new CancelledException();
    }

    private static String pathDetail(Path staging, Path quarantine, Path finalDirectory) {
        return " (staging=" + (staging == null ? "-" : staging)
                + ", quarantine=" + (quarantine == null ? "-" : quarantine)
                + ", final=" + finalDirectory + ")";
    }

    private static RuntimeImportResult failedResult(DirectCefRuntimeFailureReason reason, String message,
                                                    Throwable cause, Path packageFile, Path instanceRoot,
                                                    Path finalDirectory) {
        Path root = instanceRoot == null ? null : instanceRoot.toAbsolutePath().normalize();
        Path finalDir = finalDirectory != null ? finalDirectory
                : root == null ? null : DirectCefRuntimeDiscovery.standardDirectory(root);
        return new RuntimeImportResult(RuntimeImportResult.Status.FAILED, null,
                failure(reason, message, cause, packageFile), packageFile, null, finalDir);
    }

    private static DirectCefRuntimeException failure(DirectCefRuntimeFailureReason reason, String message, Path path) {
        return new DirectCefRuntimeException(reason, message, null, path);
    }

    private static DirectCefRuntimeException failure(DirectCefRuntimeFailureReason reason, String message,
                                                     Throwable cause, Path path) {
        return new DirectCefRuntimeException(reason, message, cause, path);
    }

    private static String key(String path) { return path.toLowerCase(Locale.ROOT); }

    private static final class CancelledException extends RuntimeException { }

    private record PackageContents(DirectCefRuntimeManifest manifest, byte[] manifestBytes,
                                   long totalExpectedBytes) { }
}
