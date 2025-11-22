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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;

/**
 * Files or root directories to archive for a single module.
 * A single instance of {@code ModuleFiles} may contain many directories for different target Java releases.
 * Many instances of {@code ModuleFiles} may exist when archiving a multi-modules project.
 */
final class ModuleFiles {
    /**
     * Name of the module being archived when the project is using module hierarchy.
     * This is {@code null} if the project is using package hierarchy, either because it is a classical
     * class-path project or because it is a single module compiled without using the module hierarchy.
     * When using module source hierarchy, {@code javac} guarantees that the module name in the output
     * directory is the name of the parent directory of {@code module-info.class}.
     */
    @Nullable
    final String moduleName;

    /**
     * Path to {@code META-INF/MANIFEST.MF}, or {@code null} if none. The manifest file
     * should be included by the {@code --manifest} option instead of as an ordinary file.
     */
    @Nullable
    Path manifest;

    /**
     * The Maven generated {@code pom.xml} and {@code pom.properties} files, or {@code null} if none.
     * This first item shall be the base directory where the files are located.
     */
    @Nullable
    List<Path> mavenFiles;

    /**
     * Fully-qualified name of the main class, or {@code null} if none.
     * This is the value to provide to the {@code --main-class} option.
     */
    private String mainClass;

    /**
     * Files or root directories to store in the <abbr>JAR</abbr> file for targeting the base Java release.
     */
    @Nonnull
    final ForRelease baseRelease;

    /**
     * Files or root directories to store in the <abbr>JAR</abbr> file for each target Java release
     * other than the base release.
     *
     * <h4>Note on duplicated versions</h4>
     * In principle, we should not have two elements with the same {@link ForRelease#version} value.
     * However, while it should not happen in default Maven builds, we do not forbid the case where
     * the same version would be defined in {@code "./META-INF"} and {@code "./<module>/META-INF"}.
     * In such case, two {@code ForRelease} instances would exist for the same Java release but with
     * two different {@link ForRelease#directory} values.
     */
    @Nonnull
    private final List<ForRelease> additionalReleases;

    /**
     * Files or root directories to archive for a single target Java release of a single module.
     */
    static final class ForRelease {
        /**
         * The target Java release, or {@code null} for the base version of the <abbr>JAR</abbr> file.
         */
        @Nullable
        final String version;

        /**
         * The root directory of all files or directories to archive.
         * This is the value to pass to the {@code -C} tool option.
         */
        @Nonnull
        private final Path directory;

        /**
         * The files or directories to include in the <var>JAR</var> file.
         * May be absolute paths or paths relative to {@link #directory}.
         */
        @Nonnull
        private final List<Path> files;

        /**
         * Creates an initially empty set of files or directories for the specified target Java release.
         *
         * @param directory the base directory of the files or directories to archive
         * @param version the target Java release, or {@code null} for the base version of the <abbr>JAR</abbr> file
         */
        private ForRelease(final Path directory, final String version) {
            this.version = version;
            this.directory = directory;
            files = new ArrayList<>();
        }

        /**
         * Adds the given path to the list of files or directories to archive.
         * This method may store a relative path instead of the absolute path.
         *
         * @param item a file or directory to archive
         * @throws IllegalArgumentException if the given path cannot be made relative to the base directory
         */
        void add(Path item) {
            if (files.isEmpty()) {
                /*
                 * In our tests, it seems that the first file after the "-C" option needs to be relative
                 * to the directory given to "-C" and all other files need to be absolute. This behavior
                 * does not seem to be documented, but we couldn't get the "jar" tool to work otherwise
                 * (except by repeating "-C" before each file).
                 */
                item = directory.relativize(item);
            }
            files.add(item);
        }

        /**
         * Adds to the given list the arguments to provide to the "jar" tool for this version.
         * Elements added to the list shall be instances of {@link String} or {@link Path}.
         *
         * @param addTo the list where to add the arguments as {@link String} or {@link Path} instances
         */
        private void arguments(final List<Object> addTo) {
            if (files.isEmpty()) {
                // Happen if both `Archiver.moduleHierarchy` and `Archiver.packageHierarchy` are empty.
                return;
            }
            if (version != null) {
                addTo.add("--release");
                addTo.add(version);
            }
            addTo.add("-C");
            addTo.add(directory);
            addTo.addAll(files);
        }

        /**
         * {@return a string representation for debugging purposes}
         */
        @Override
        public String toString() {
            return getClass().getSimpleName() + '[' + (version != null ? version : "base") + " = "
                    + directory.getFileName() + ']';
        }
    }

    /**
     * Creates an initially empty set of files or directories.
     *
     * @param moduleName the module name if using module hierarchy, or {@code null} if using package hierarchy
     * @param directory the directory of the classes targeting the base Java release
     */
    ModuleFiles(String moduleName, Path directory) {
        this.moduleName = moduleName;
        baseRelease = new ForRelease(directory, null);
        additionalReleases = new ArrayList<>();
    }

    /**
     * Returns the root directory of all files or directories to archive for this module.
     *
     * @return the root directory of this module.
     */
    public Path directory() {
        return baseRelease.directory;
    }

    /**
     * Returns whether this module can be skipped. This is {@code true} if this module has no file to archive,
     * ignoring Maven-generated files, and {@code skipIfEmpty} is {@code true}. This method should be invoked
     * even in the trivial case where the {@code skipIfEmpty} argument is {@code false}.
     *
     * @param skipIfEmpty value of {@link AbstractJarMojo#skipIfEmpty}
     * @return whether this module can be skipped
     */
    public boolean canSkip(boolean skipIfEmpty) {
        additionalReleases.removeIf((v) -> v.files.isEmpty());
        return skipIfEmpty && baseRelease.files.isEmpty() && additionalReleases.isEmpty();
    }

    /**
     * Returns an initially empty set of files or directories for the specified target Java release.
     *
     * @param directory the base directory of the files to archive
     * @param version the target Java release, or {@code null} for the base version
     * @return container where to declare files and directories to archive
     */
    ForRelease newTargetRelease(Path directory, String version) {
        var release = new ForRelease(directory, version);
        additionalReleases.add(release);
        return release;
    }

    /**
     * Sets the {@code --main-class} option to the value of the {@code Main-Class} entry of the given manifest.
     *
     * @param merged combination of existing {@code MANIFEST.MF} and manifest inferred from configuration, or null
     * @return whether the given manifest has been modified by this method
     */
    boolean setMainClass(Manifest merged) {
        if (merged == null || mainClass != null) {
            return false;
        }
        // We need to remove the attribute, otherwise it will conflict with `--main-class`.
        mainClass = (String) merged.getMainAttributes().remove(Attributes.Name.MAIN_CLASS);
        return mainClass != null;
    }

    /**
     * Adds to the given list the arguments to provide to the "jar" tool for each version.
     * Elements added to the list shall be instances of {@link String} or {@link Path}.
     * Callers should have added the following options (if applicable) before to invoke this method:
     *
     * <ul>
     *   <li>{@code --create}</li>
     *   <li>{@code --no-compress}</li>
     *   <li>{@code --date} followed by the output time stamp</li>
     *   <li>{@code --module-version} followed by module version</li>
     *   <li>{@code --hash-modules} followed by patters of module names</li>
     *   <li>{@code --module-path} followed by module path</li>
     * </ul>
     *
     * This method adds the following options:
     *
     * <ul>
     *   <li>{@code --file} followed by the name of the <abbr>JAR</abbr> file</li>
     *   <li>{@code --manifest} followed by path to the manifest file</li>
     *   <li>{@code --main-class} followed by fully qualified name class</li>
     *   <li>{@code --release} followed by Java target release</li>
     *   <li>{@code -C} followed by directory</li>
     *   <li>files or directories to archive</li>
     * </ul>
     *
     * @param jarFile the <abbr>JAR</abbr> file to create when package hierarchy is used
     * @param addTo the list where to add the arguments as {@link String} or {@link Path} instances
     * @return the actual <abbr>JAR</abbr> file to create, taking module in account
     */
    Path arguments(Path jarFile, final List<Object> addTo) {
        addTo.add("--file");
        if (moduleName != null) {
            // TODO: make a bigger effort to derive from `jarFile`.
            jarFile = jarFile.resolveSibling(moduleName + ".jar");
        }
        addTo.add(jarFile);
        if (manifest != null) {
            addTo.add("--manifest");
            addTo.add(manifest);
        }
        if (mainClass != null) {
            addTo.add("--main-class");
            addTo.add(mainClass);
        }
        if (mavenFiles != null) {
            addTo.add("-C");
            addTo.addAll(mavenFiles);
        }
        baseRelease.arguments(addTo);
        additionalReleases.forEach((release) -> release.arguments(addTo));
        return jarFile;
    }

    /**
     * Dumps the tool options together with the list of files into a debug file.
     * This is invoked in case of compilation failure, or if debug is enabled.
     * The arguments can be separated by spaces or by new line characters.
     * File name should be between double quotation marks.
     *
     * @param baseDir project base directory for relativizing the arguments
     * @param debugDirectory the directory where to write the debug file
     * @param classifier the classifier (e.g. "tests"), or {@code null} if none
     * @param arguments the arguments formatted by {@link #arguments(Path, List)}
     * @return the debug file where arguments have been written
     * @throws IOException if an error occurred while writing the debug file
     */
    Path writeDebugFile(Path baseDir, Path debugDirectory, String classifier, List<Object> arguments)
            throws IOException {
        var filename = new StringBuilder("jar");
        if (moduleName != null) {
            filename.append('-').append(moduleName);
        }
        if (classifier != null) {
            filename.append('-').append(classifier);
        }
        Path debugFile = debugDirectory.resolve(filename.append(".args").toString());
        try (BufferedWriter out = Files.newBufferedWriter(debugFile)) {
            boolean isNewLine = true;
            for (Object argument : arguments) {
                if (argument instanceof String option) {
                    if (!isNewLine) {
                        if (option.startsWith("--") || option.equals("-C")) {
                            out.newLine();
                        } else {
                            out.write(' ');
                        }
                    }
                    out.write(option);
                    isNewLine = false;
                } else {
                    var file = (Path) argument;
                    try {
                        file = baseDir.relativize(file);
                    } catch (IllegalArgumentException e) {
                        // Ignore, keep the absolute path.
                    }
                    if (!isNewLine) {
                        out.write(' ');
                    }
                    out.write('"');
                    out.write(file.toString());
                    out.write('"');
                    out.newLine();
                    isNewLine = true;
                }
            }
        }
        return debugFile;
    }

    /**
     * {@return a string representation for debugging purposes}
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + '[' + (moduleName != null ? moduleName : "no module") + ']';
    }
}
