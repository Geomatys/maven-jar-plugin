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

import javax.lang.model.SourceVersion;

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
import org.apache.maven.api.annotations.Nullable;
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
 *       <li>{@code META-INF/versions-modular/<module>/}</li>
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
     * This is part of <abbr>JAR</abbr> file specification.
     */
    private static final String VERSIONS = "versions";

    /**
     * The {@value} directory.
     * This is Maven-specific.
     */
    private static final String VERSIONS_MODULAR = "versions-modular";

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
     * Whether the matchers accept all files. In such case, we can declare whole directories
     * to the {@code jar} tool instead of scaning the directory tree ourselves.
     */
    private final boolean acceptsAllFiles;

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
     * The current target Java release, or {@code null} if none.
     */
    @Nullable
    private String currentTargetVersion;

    /**
     * Identification of the kinds of directories being traversed.
     * The length of this list is the depth in the directory hierarchy.
     * The last element identifies the type of the current directory.
     */
    private final List<LocationType> locations;

    /**
     * Whether to check when a file is the {@code MANIFEST.MF} file.
     * This is allowed only when scanning the content of a {@code META-INF} directory.
     */
    private boolean checkForManifest;

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
        locations = new ArrayList<>();
        fileMatcher = PathSelector.of(directory, includes, excludes);
        if (fileMatcher instanceof PathSelector ps && ps.canFilterDirectories()) {
            directoryMatcher = (path) -> ps.couldHoldSelected(path);
        } else {
            directoryMatcher = PathSelector.INCLUDES_ALL;
        }
        acceptsAllFiles = directoryMatcher == PathSelector.INCLUDES_ALL && fileMatcher == PathSelector.INCLUDES_ALL;
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
     * @param directory a {@code "<module>"} or {@code "META-INF/versions-modular/<module>"} directory
     */
    private void enterModuleDirectory(Path directory) {
        String moduleName = directory.getFileName().toString();
        currentModule = moduleHierarchy.computeIfAbsent(moduleName, (name) -> new ModuleFiles(name, directory));
        currentFilesToArchive = currentModule.newTargetRelease(directory, null);
    }

    /**
     * Declares that the given directory is the base directory of a target Java version.
     *
     * @param directory a {@code "META-INF/versions/<n>"} or {@code "META-INF/versions-modular/<n>"} directory
     */
    private void enterVersionDirectory(Path directory) {
        currentTargetVersion = directory.getFileName().toString();
        currentFilesToArchive = currentModule.newTargetRelease(directory, currentTargetVersion);
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
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
        LocationType location;
        if (locations.isEmpty()) {
            location = LocationType.ROOT;
        } else {
            if (!directoryMatcher.matches(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            checkForManifest = false;
            location = locations.get(locations.size() - 1); // TODO: replace by `getLast()` when allowed to use JDK21.
            switch (location) {
                case ROOT:
                    /*
                     * Entering in any subdirectory of `target/classes` (or other directory to archive).
                     * We need to handle `META-INF` and modules in a special way, and archive the rest.
                     */
                    if (directory.endsWith(MetadataFiles.META_INF)) {
                        location = LocationType.META_INF;
                        checkForManifest = true;
                    } else if (Files.isRegularFile(directory.resolve(MODULE_DESCRIPTOR_FILE_NAME))) {
                        location = LocationType.NAMED_MODULE;
                        enterModuleDirectory(directory);
                    } else {
                        location = LocationType.RESOURCES;
                    }
                    break;

                case META_INF:
                    /*
                     * Entering in a subdirectory of `META-INF` or `<module>/META-INF`. We will need to handle
                     * `MANIFEST.MF`, `versions` and `versions-modular` in a special way, and archive the rest.
                     */
                    if (detectMultiReleaseJar && directory.endsWith(VERSIONS)) {
                        location = LocationType.VERSIONS;
                    } else if (directory.endsWith(VERSIONS_MODULAR)) {
                        if (!detectMultiReleaseJar) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        location = LocationType.VERSIONS_MODULAR;
                    } else {
                        location = LocationType.RESOURCES;
                    }
                    break;

                case VERSIONS:
                    /*
                     * Entering in a `META-INF/versions/<n>/` directory for a specific target Java release.
                     * May also be a `<module>/META-INF/versions/<n>/` directory, even if the latter is not
                     * the layout generated by Maven Compiler Plugin.
                     */
                    enterVersionDirectory(directory);
                    location = LocationType.RESOURCES;
                    break;

                case VERSIONS_MODULAR:
                    /*
                     * Entering in a `META-INF/versions-modular/<n>/` directory for a specific target Java release.
                     * That directory contains all modules for the version.
                     */
                    resetToPackageHierarchy(); // No module in particular yet.
                    enterVersionDirectory(directory);
                    location = LocationType.MODULES;
                    break;

                case MODULES:
                    /*
                     * Entering in a `META-INF/versions-modular/<n>/<module>` directory.
                     */
                    enterModuleDirectory(directory);
                    location = LocationType.NAMED_MODULE;
                    break;

                case NAMED_MODULE:
                    /*
                     * Entering in a `<module>` or `META-INF/versions-modular/<n>/<module>` subdirectory.
                     * A module could have its own `META-INF` subdirectory, so we need to check again.
                     */
                    if (directory.endsWith(MetadataFiles.META_INF)) {
                        location = LocationType.META_INF;
                        checkForManifest = true;
                    } else {
                        location = LocationType.RESOURCES;
                    }
                    break;
            }
        }
        if (acceptsAllFiles && location == LocationType.RESOURCES) {
            currentFilesToArchive.add(directory);
            return FileVisitResult.SKIP_SUBTREE;
        } else {
            locations.add(location);
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Updates the {@code Archiver} state if we finished to scan the content of a module.
     *
     * @param directory the directory which has been traversed
     * @param error the error that occurred while traversing the directory, or {@code null} if none
     */
    @Override
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
        if (error != null) {
            throw error;
        }
        switch (locations.remove(locations.size() - 1)) { // TODO: replace by `removeLast()` when allowed to use JDK21.
            case NAMED_MODULE:
                // Exited the directory of a whole module.
                resetToPackageHierarchy();
                break;

            case VERSIONS_MODULAR:
            case VERSIONS:
                // Exited the directory for one target Java release.
                currentFilesToArchive = currentModule.baseRelease;
                currentTargetVersion = null;
                break;

            case META_INF:
                checkForManifest = true;
                break;
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
            if (checkForManifest && currentModule.manifest == null && file.endsWith(MetadataFiles.MANIFEST)) {
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
    final class Writer {
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
        private final Manifest manifestFromPlugin;

        /**
         * The file from which {@link #manifestFromPlugin} has been read, or {@code null} if none.
         * If non-null, reading that file shall produce the same manifest as {@link #manifestFromPlugin}.
         * It implies that this field shall be {@code null} if {@link #manifestFromPlugin} is the result
         * of merging elements specified in {@code <archive>} with a file specified in the plugin configuration.
         */
        private final Path manifestFile;

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
         * @param manifest manifest built from plugin configuration, or {@code null} if none
         * @param archive the archive configuration
         * @throws IOException if an error occurred while reading the manifest file
         */
        Writer(AbstractJarMojo mojo, Manifest manifest, MavenArchiveConfiguration archive) throws IOException {
            project = mojo.getProject();
            tool = mojo.getJarTool();
            buildDir = mojo.getOutputDirectory();
            classifier = AbstractJarMojo.nullIfAbsent(mojo.getClassifier());
            jarFile = mojo.getJarFile(buildDir, mojo.getFinalName(), classifier);
            outputTimestamp = mojo.getOutputTimestamp();
            logger = mojo.getLog();

            var buffer = new StringWriter();
            messages = buffer.getBuffer();
            messageWriter = new PrintWriter(buffer);

            buffer = new StringWriter();
            errors = buffer.getBuffer();
            errorWriter = new PrintWriter(buffer);

            arguments = new ArrayList<>();
            result = new LinkedHashMap<>();
            this.archive = archive;

            Path file = archive.getManifestFile();
            if (file != null) {
                try (InputStream in = Files.newInputStream(file)) {
                    // No need to wrap in `BufferedInputStream`.
                    if (manifest != null) {
                        manifest.read(in);
                        file = null; // Because the manifest is the result of a merge.
                    } else {
                        manifest = new Manifest(in);
                    }
                }
            }
            if (manifest != null) {
                if (detectMultiReleaseJar) {
                    manifest.getMainAttributes().remove(Attributes.Name.MULTI_RELEASE);
                }
                if (!isReproducible()) {
                    // If reproducible build was not requested, let the tool declares itself.
                    // This is a workaround until we port Maven archiver to this JAR plugin.
                    manifest.getMainAttributes().remove("Created-By");
                }
            }
            manifestFromPlugin = manifest;
            manifestFile = file;
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
            Manifest manifest = manifestFromPlugin;
            boolean writeTemporaryManifest = (manifest != null && manifestFile == null);
            if (files.manifest == null) {
                files.manifest = manifestFile;
            } else if (manifestFile == null || !Files.isSameFile(manifestFile, files.manifest)) {
                try (InputStream in = Files.newInputStream(files.manifest)) {
                    // No need to wrap in `BufferedInputStream`.
                    if (manifest != null) {
                        manifest = new Manifest(manifest);
                        manifest.read(in);
                        writeTemporaryManifest = true;
                    } else {
                        manifest = new Manifest(in);
                    }
                }
            }
            writeTemporaryManifest |= files.setMainClass(manifest);
            if (manifest != null) {
                String name = manifest.getMainAttributes().getValue("Automatic-Module-Name");
                if (name != null && !SourceVersion.isName(name)) {
                    throw new MojoException("Invalid automatic module name: \"" + name + "\".");
                }
            }
            try (MetadataFiles temporaryMetadataFiles = new MetadataFiles(buildDir)) {
                if (writeTemporaryManifest) {
                    files.manifest = temporaryMetadataFiles.addManifest(manifest);
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
