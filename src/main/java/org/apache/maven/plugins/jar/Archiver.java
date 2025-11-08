/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.jar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import org.apache.maven.api.Project;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.shared.archiver.MavenArchiveConfiguration;

/**
 * Dispatch the files in the output directory into the <abbr>JAR</abbr> files to create.
 * Instead of just archiving as-is the content of the output directory, this class separates
 * the following subdirectories to the options listed below:
 *
 * <ul>
 *   <li>The {@code META-INF/MANIFEST.MF} file will be given to the {@code --manifest} option.</li>
 *   <li>Files in the following directories will be given to the {@code --release} option:
 *     <ul>
 *       <li>{@code META-INF/versions/}</li>
 *       <li>{@code META-INF/versions/<module>/}</li>
 *       <li>{@code <module>/META-INF/versions/}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * The reason for using the options is that they allow the {@code jar} tool to perform additional verifications.
 * For example, when using the {@code --release} option, {@code jar} verifies the <abbr>API</abbr> compatibility.
 */
final class Archiver extends SimpleFileVisitor<Path> {
    /**
     * The file to check for deciding whether the <abbr>JAR</abbr> is modular.
     */
    private static final String MODULE_DESCRIPTOR_FILE_NAME = "module-info.class";

    /**
     * The {@value} directory.
     */
    static final String META_INF = "META-INF";

    /**
     * The {@value} file.
     */
    static final String MANIFEST = "MANIFEST.MF";

    /**
     * The {@value} directory.
     */
    private static final String VERSIONS = "versions";

    /**
     * Whether to detect multi-release <abbr>JAR</abbr> files.
     */
    private final boolean detectMultiReleaseJar;

    /**
     * Combination of includes and excludes path matcher applied on files.
     */
    @Nonnull
    private final PathMatcher fileMatcher;

    /**
     * Combination of includes and excludes path matcher applied on directories.
     */
    @Nonnull
    private final PathMatcher directoryMatcher;

    /**
     * Whether at least one matcher does not accept all files. In such case, we cannot declare
     * whole directories to the {@code jar} tool and need to scan the directory tree ourselves.
     */
    private final boolean applyMatcherOnEachFile;

    /**
     * Files found in the output directory when package hierarchy is used.
     * At most one of {@code packageHierarchy} and {@link #moduleHierarchy} can be non-empty.
     */
    @Nonnull
    private final ModuleFiles packageHierarchy;

    /**
     * Files found in the output directory when module hierarchy is used. Keys are module names.
     * At most one of {@link #packageHierarchy} and {@code moduleHierarchy} can be non-empty.
     */
    @Nonnull
    private final Map<String, ModuleFiles> moduleHierarchy;

    /**
     * The current module being archived. This field is updated every times that {@code Archiver}
     * enters in a new module directory.
     */
    @Nonnull
    private ModuleFiles currentModule;

    /**
     * The module and target Java release currently being scanned. This field is updated every times that
     * {@code Archiver} enters in a new module directory or in a new target Java release for a given module.
     */
    @Nonnull
    private ModuleFiles.ForRelease currentFilesToArchive;

    /**
     * Creates a new archiver.
     *
     * @param directory the base directory of the files to archive
     * @param includes the patterns of the files to include, or null or empty for including all files
     * @param excludes the patterns of the files to exclude, or null or empty for no exclusion
     * @param detectMultiReleaseJar whether to detect multi-release <abbr>JAR</abbr> files
     */
    Archiver(Path directory, Collection<String> includes, Collection<String> excludes, boolean detectMultiReleaseJar) {
        this.detectMultiReleaseJar = detectMultiReleaseJar;
        fileMatcher = PathSelector.of(directory, includes, excludes);
        if (fileMatcher instanceof PathSelector ps && ps.canFilterDirectories()) {
            directoryMatcher = (path) -> ps.couldHoldSelected(path);
        } else {
            directoryMatcher = PathSelector.INCLUDES_ALL;
        }
        applyMatcherOnEachFile =
                (directoryMatcher != PathSelector.INCLUDES_ALL) || (fileMatcher != PathSelector.INCLUDES_ALL);
        packageHierarchy = new ModuleFiles(null, directory);
        moduleHierarchy = new LinkedHashMap<>();
        resetToPackageHierarchy();
    }

    /**
     * Resets this {@code Archiver} to the state where a package hierarchy is presumed.
     */
    private void resetToPackageHierarchy() {
        currentModule = packageHierarchy;
        currentFilesToArchive = currentModule.baseRelease;
    }

    /**
     * Declares that the given directory is the base directory of a module.
     * For an output generated by {@code javac} from a module source hierarchy,
     * the directory name is guaranteed to be the module name.
     *
     * @param directory the directory to test
     */
    private void enterModuleDirectory(Path directory) {
        String moduleName = directory.getFileName().toString();
        currentModule = moduleHierarchy.computeIfAbsent(moduleName, (name) -> new ModuleFiles(name, directory));
        currentFilesToArchive = currentModule.newTargetRelease(directory, null);
    }

    /**
     * Checks whether the given directory is a {@code "META-INF"} directory. To be accepted, the directory must
     * be located either in the root directory of a module output or in the root directory of the project output.
     */
    private boolean isMetaInfDirectory(Path directory) {
        return directory.endsWith(META_INF) && currentModule.directory().equals(directory.getParent());
    }

    /**
     * Checks whether the given directory is a {@code "META-INF/versions"} directory.
     */
    private boolean isVersionsDirectory(Path directory) {
        return detectMultiReleaseJar && directory.endsWith(VERSIONS) && isMetaInfDirectory(directory.getParent());
    }

    /**
     * Returns whether the given directory is a module directory.
     * This method uses {@value #MODULE_DESCRIPTOR_FILE_NAME} as a sentinel value.
     */
    private static boolean isModuleDirectory(Path directory) {
        return Files.isRegularFile(directory.resolve(MODULE_DESCRIPTOR_FILE_NAME));
    }

    /**
     * Determines if the given directory should be scanned for files to archive.
     * This method may also update {@link #currentFilesToArchive} if it detects
     * that we are entering in a new module or a new target Java release.
     *
     * @param directory the directory which will be traversed
     * @param attributes the directory's basic attributes
     */
    @Override
    public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
        final Path outputDirectory = packageHierarchy.directory();
        if (directory.equals(outputDirectory)) {
            // Entering in the root directory.
            return FileVisitResult.CONTINUE;
        }
        if (directoryMatcher.matches(directory)) {
            final Path parent = directory.getParent();
            if (outputDirectory.equals(parent)) {
                /*
                 * Entering in a sub-directory of the root output directory specified to constructor.
                 * Check if this is a module directory, using `module-info.class` as a sentinel value.
                 */
                if (isModuleDirectory(directory)) {
                    enterModuleDirectory(directory);
                    return FileVisitResult.CONTINUE;
                }
            }
            if (isMetaInfDirectory(directory) || isVersionsDirectory(directory)) {
                /*
                 * Entering in the `META-INF/` directory or in a `<module>/META-INF/` sub-directory.
                 * Scan file-by-file because `MANIFEST.MF` and `versions/` will need special handling.
                 */
                return FileVisitResult.CONTINUE;
            }
            if (isVersionsDirectory(parent)) {
                /*
                 * Entering in a `META-INF/versions/<n>/` directory for a specific target Java release.
                 * May also be a `<module>/META-INF/versions/<n>/` directory, even of the latter is not
                 * the layout generated by Maven Compiler Plugin.
                 */
                String version = directory.getFileName().toString();
                currentFilesToArchive = currentModule.newTargetRelease(directory, version);
                return FileVisitResult.CONTINUE;
            }
            if (currentFilesToArchive.version != null && parent.equals(currentFilesToArchive.directory)) {
                /*
                 * Maybe entering in a `META-INF/versions/<n>/<module>/` directory. There is an ambiguity
                 * about whether `<module>` is really a module or a package, depending on whether project
                 * is using module hierarchy or package hierarchy respectively. We have module hierarchy
                 * if `moduleHierarchy` is not empty, but maybe the latter is empty because we have not
                 * yet walked on the base directories. So we need to double-check.
                 */
                boolean isModule = true;
                if (moduleHierarchy.isEmpty()) {
                    // Ambiguity if we have not yet walked through the base directory.
                    isModule = isModuleDirectory(directory);
                    if (!isModule) {
                        String moduleName = directory.getFileName().toString();
                        isModule = isModuleDirectory(outputDirectory.resolve(moduleName));
                    }
                }
                if (isModule) {
                    enterModuleDirectory(directory);
                    return FileVisitResult.CONTINUE;
                }
            }
            if (applyMatcherOnEachFile) {
                // There is an include/exclude matcher: we need to scan all files individually.
                return FileVisitResult.CONTINUE;
            }
            // Add the whole directory. The JAR tool will traverse the directory itself.
            currentFilesToArchive.add(directory);
        }
        return FileVisitResult.SKIP_SUBTREE;
    }

    /**
     * Updates the {@code Archiver} state if we finished to scan the content of a module.
     *
     * @param directory the directory which has been traversed
     * @param error the error that occurred while traversing the directory, or {@code null} if none
     */
    @Override
    public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
        if (error != null) {
            throw error;
        }
        if (directory.equals(currentFilesToArchive.directory)) {
            // Exited the directory for one target Java release.
            currentFilesToArchive = currentModule.baseRelease;
        } else if (directory.equals(currentModule.directory())) {
            // Exited the directory of a whole module.
            resetToPackageHierarchy();
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Archives a single file if accepted by the matcher.
     *
     * @param file the file
     * @param attributes the file's basic attributes
     */
    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
        if (fileMatcher.matches(file)) {
            if (currentModule.manifest == null && file.endsWith(MANIFEST) && isMetaInfDirectory(file.getParent())) {
                currentModule.manifest = file;
            } else {
                currentFilesToArchive.add(file);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Writer of <abbr>JAR</abbr> files using the information collected by the enclosing class.
     */
    final class JAR {
        /**
         * The Maven project for which to create an archive.
         */
        private final Project project;

        /**
         * The build directory. This is usually {@code ${baseDir}/target/}.
         */
        private final Path buildDir;

        /**
         * The classifier (e.g. "test"), or {@code null} if none.
         */
        private final String classifier;

        /**
         * The <abbr>JAR</abbr> file to create when package hierarchy is used.
         * This is usually a file placed in the {@link #buildDir} directory.
         */
        private final Path jarFile;

        /**
         * The tool to use for creating the <abbr>JAR</abbr> files.
         */
        private final ToolProvider tool;

        /**
         * Where to send messages emitted by the "jar" tool.
         */
        private final PrintWriter messageWriter;

        /**
         * Where to send error messages emitted by the "jar" tool.
         */
        private final PrintWriter errorWriter;

        /**
         * Where the messages sent to {@link #messageWriter} are stored.
         */
        private final StringBuffer messages;

        /**
         * Where the messages sent to {@link #errorWriter} are stored.
         */
        private final StringBuffer errors;

        /**
         * A buffer for the arguments given to the "jar" tool, reused for each module.
         * Each element of the list shall be instances of either {@link String} or {@link Path}.
         */
        private final List<Object> arguments;

        /**
         * The paths to the created archive files.
         */
        private final Map<String, Path> result;

        /**
         * Manifest to merge with the manifest found in the files to archive.
         * This is a manifest built from the {@code <archive>} plugin configuration.
         * Can be {@code null} if there is noting to add to the existing manifests.
         */
        private final Manifest manifest;

        /**
         * The archive configuration to use.
         */
        private final MavenArchiveConfiguration archive;

        /**
         * The timestamp in ISO-8601 extended offset date-time, or {@code null} if none.
         * If user provided a value in seconds, it shall have been converted to ISO-8601.
         * This is used for reproducible builds.
         */
        private final String outputTimestamp;

        /**
         * Where to send informative or error messages.
         */
        private final Log logger;

        /**
         * Creates a new writer.
         *
         * @param mojo the <abbr>MOJO</abbr> from which to get the configuration
         * @param buildDir the build directory (usually {@code ${baseDir}/target/})
         * @param finalName name of the generated <abbr>JAR</abbr> file
         * @param manifest manifest to merge with the manifest found in the files to archive
         * @param archive the archive configuration
         */
        JAR(
                AbstractJarMojo mojo,
                Path buildDir,
                String finalName,
                Manifest manifest,
                MavenArchiveConfiguration archive) {
            this.project = mojo.getProject();
            this.tool = mojo.getJarTool();
            this.buildDir = buildDir;
            this.classifier = AbstractJarMojo.nullIfAbsent(mojo.getClassifier());
            this.jarFile = mojo.getJarFile(buildDir, finalName, classifier);
            this.manifest = manifest;
            this.archive = archive;
            this.outputTimestamp = mojo.getOutputTimestamp();
            this.logger = mojo.getLog();
            var writer = new StringWriter();
            messages = writer.getBuffer();
            messageWriter = new PrintWriter(writer);
            writer = new StringWriter();
            errors = writer.getBuffer();
            errorWriter = new PrintWriter(writer);
            arguments = new ArrayList<>();
            result = new LinkedHashMap<>();
            if (detectMultiReleaseJar) {
                manifest.getMainAttributes().remove(Attributes.Name.MULTI_RELEASE);
            }
            if (!isReproducible()) {
                // If reproducible build was not requested, let the tool declares itself.
                // This is a workaround until we port Maven archiver to this JAR plugin.
                manifest.getMainAttributes().remove("Created-By");
            }
        }

        /**
         * Whether reproducible build was requested.
         * In current version, the output time stamp is used as a sentinel value.
         */
        public boolean isReproducible() {
            return outputTimestamp != null;
        }

        /**
         * Writes all <abbr>JAR</abbr> files.
         *
         * @return the paths to the created archive files
         * @throws MojoException if an error occurred during the execution of the "jar" tool
         * @throws IOException if an error occurred while reading or writing a manifest file
         */
        @SuppressWarnings("ReturnOfCollectionOrArrayField")
        public Map<String, Path> writeAllFiles(boolean skipIfEmpty) throws IOException {
            for (ModuleFiles module : moduleHierarchy.values()) {
                if (!module.canSkip(skipIfEmpty)) {
                    writeSingleFile(module);
                }
            }
            // `packageHierarchy` is expected to be empty, and should be skipped, if `moduleHierarchy` was used.
            if (!packageHierarchy.canSkip(skipIfEmpty || !moduleHierarchy.isEmpty())) {
                writeSingleFile(packageHierarchy);
            }
            return result;
        }

        /**
         * Creates the <abbr>JAR</abbr> files for the specified set of files.
         * If the operation fails, an error message may be available in the
         * {@link #errors} buffer.
         *
         * @throws MojoException if an error occurred during the execution of the "jar" tool
         * @throws IOException if an error occurred while reading or writing a manifest file
         */
        private void writeSingleFile(final ModuleFiles files) throws IOException {
            /*
             * If `MANIFEST.MF` entries were specified by JAR plugin configuration,
             * merge those entries with the content of `MANIFEST.MF` file found in
             * the files to archive.
             */
            boolean modifiedManifest = false;
            Manifest merged = manifest;
            if (files.manifest != null) {
                try (InputStream in = Files.newInputStream(files.manifest)) {
                    // No need to wrap in `BufferedInputStream`.
                    if (merged != null) {
                        merged = new Manifest(merged);
                        merged.read(in);
                        modifiedManifest = true;
                    } else {
                        merged = new Manifest(in);
                    }
                }
            }
            // Manifest explicitly defined in plugin configuration overwrites manifest found in output classes.
            Path explicitManifest = archive.getManifestFile();
            if (explicitManifest != null) {
                try (InputStream in = Files.newInputStream(explicitManifest)) {
                    merged.read(in);
                    modifiedManifest = true;
                }
            }
            modifiedManifest |= files.setMainClass(merged);
            try (MetadataFiles temporaryMetadataFiles = new MetadataFiles(buildDir)) {
                if (modifiedManifest) {
                    files.manifest = temporaryMetadataFiles.addManifest(merged);
                }
                if (archive.isAddMavenDescriptor()) {
                    files.mavenFiles = temporaryMetadataFiles.addPOM(project, archive, isReproducible());
                }
                /*
                 * Execute the `jar` tool with arguments determined by the values dispatched
                 * in the various fields of the `ModuleFiles`. Non-error messages are logged
                 * and error messages are stored for later retrieval in the `errors` buffer.
                 */
                arguments.add("--create");
                if (!archive.isCompress()) {
                    arguments.add("--no-compress");
                }
                if (outputTimestamp != null) {
                    arguments.add("--date");
                    arguments.add(outputTimestamp);
                }
                final Path actualFile = files.arguments(jarFile, arguments);
                result.put(files.moduleName, actualFile);
                String[] options = new String[arguments.size()];
                Arrays.setAll(options, (i) -> arguments.get(i).toString());
                int status = tool.run(messageWriter, errorWriter, options);
                if (!messages.isEmpty()) {
                    logger.info(messages);
                    messages.setLength(0);
                }
                if (status != 0 || logger.isDebugEnabled()) {
                    Path debugFile = files.writeDebugFile(project.getBasedir(), buildDir, classifier, arguments);
                    temporaryMetadataFiles.cancelFileDeletion();
                    if (status != 0) {
                        String error = reportError(debugFile);
                        StringBuilder message = new StringBuilder("Cannot create the \"")
                                .append(actualFile.getFileName())
                                .append("\" archive file.");
                        if (error.isBlank()) {
                            message.append('.');
                        } else {
                            message.append(": ").append(error);
                        }
                        throw new MojoException(message.toString());
                    }
                }
            }
            arguments.clear();
        }

        /**
         * Sends an error message to the logger if non-blank, then log a tip for testing from the command-line.
         *
         * @param debugFile the file containing the "jar" too arguments
         * @return the error message, or an empty string of none
         */
        private String reportError(Path debugFile) {
            final String message = errors.toString().stripTrailing();
            if (!message.isBlank()) {
                logger.error(message);
            }
            final var commandLine = new StringBuilder("For trying to archive from the command-line, use:");
            Path dir = project.getBasedir();
            try {
                debugFile = dir.relativize(debugFile);
            } catch (IllegalArgumentException e) {
                // Ignore, keep the absolute path.
            }
            if (dir != null) {
                try {
                    dir = Path.of(System.getProperty("user.dir")).relativize(dir);
                } catch (IllegalArgumentException e) {
                    // Ignore, keep the absolute path.
                }
                String chdir = dir.toString();
                if (!chdir.isEmpty()) {
                    boolean isWindows = (File.separatorChar == '\\');
                    commandLine
                            .append(System.lineSeparator())
                            .append("    ")
                            .append(isWindows ? "chdir " : "cd ")
                            .append(chdir);
                }
            }
            commandLine
                    .append(System.lineSeparator())
                    .append("    ")
                    .append(tool.name())
                    .append(" @")
                    .append(debugFile);
            logger.info(commandLine);
            return message.trim();
        }
    }
}
