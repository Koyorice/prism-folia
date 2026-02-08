/*
 * This file is part of LuckPerms, licensed under the MIT License.
 * It has been modified for use in Prism.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.prism_mc.prism.loader.services.dependencies;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.prism_mc.prism.loader.services.configuration.ConfigurationService;
import org.prism_mc.prism.loader.services.dependencies.classpath.ClassPathAppender;
import org.prism_mc.prism.loader.services.dependencies.loader.IsolatedClassLoader;
import org.prism_mc.prism.loader.services.dependencies.relocation.Relocation;
import org.prism_mc.prism.loader.services.dependencies.relocation.RelocationHandler;
import org.prism_mc.prism.loader.services.logging.LoggingService;
import org.prism_mc.prism.loader.services.scheduler.ThreadPoolScheduler;

public class DependencyService {

    /**
     * The logging service.
     */
    private final LoggingService loggingService;

    /**
     * The configuration service.
     */
    private final ConfigurationService configurationService;

    /**
     * The dependency registry.
     */
    private final DependencyRegistry registry;

    /**
     * The class path appender.
     */
    private final ClassPathAppender classPathAppender;

    /**
     * The cache directory.
     */
    private final Path cacheDirectory;

    /**
     * Cached relocation handler instance.
     */
    private final RelocationHandler relocationHandler;

    /**
     * The thread pool scheduler.
     */
    private final ThreadPoolScheduler threadPoolScheduler;

    /**
     * A map of dependencies which have already been loaded.
     */
    private final EnumMap<Dependency, Path> loaded = new EnumMap<>(Dependency.class);

    /**
     * A map of isolated classloaders which have been created.
     */
    private final Map<ImmutableSet<Dependency>, IsolatedClassLoader> loaders = new HashMap<>();

    /**
     * Constructor.
     *
     * @param loggingService The logging service
     * @param configurationService The configuration service
     * @param dataPath The plugin data path
     * @param classPathAppender The class path appender
     * @param threadPoolScheduler The scheduler adapter
     */
    public DependencyService(
        LoggingService loggingService,
        ConfigurationService configurationService,
        Path dataPath,
        ClassPathAppender classPathAppender,
        ThreadPoolScheduler threadPoolScheduler
    ) {
        this.loggingService = loggingService;
        this.configurationService = configurationService;
        this.classPathAppender = classPathAppender;
        this.threadPoolScheduler = threadPoolScheduler;
        this.registry = new DependencyRegistry();
        this.cacheDirectory = createDependenciesDirectory(dataPath);
        this.relocationHandler = new RelocationHandler(this);
    }

    /**
     * Create the dependency cache directory if it does not exist.
     *
     * @param dataPath The plugin's data path
     * @return The cache path
     */
    private Path createDependenciesDirectory(Path dataPath) {
        Path cacheDirectory = dataPath.resolve("libs");
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create libs directory", e);
        }

        return cacheDirectory;
    }

    /**
     * Download a dependency.
     *
     * @param dependency The dependency
     * @return The download path
     * @throws DependencyDownloadException The download exception
     */
    private Path downloadDependency(Dependency dependency) throws DependencyDownloadException {
        Path file = this.cacheDirectory.resolve(dependency.fileName(null));
        Path remappedFile = this.cacheDirectory.resolve(dependency.fileName("remapped"));

        // If the either file already exists, don't attempt to re-download it.
        if (Files.exists(file) || Files.exists(remappedFile)) {
            return file;
        }

        loggingService.info("Downloading dependency {0}...", dependency.name().toLowerCase(Locale.ENGLISH));

        DependencyDownloadException lastError = null;

        // Attempt to download the dependency from each repo in order.
        for (DependencyRepository repo : dependency.repositories()) {
            try {
                repo.download(dependency, file);
                return file;
            } catch (DependencyDownloadException e) {
                lastError = e;
            }
        }

        throw Objects.requireNonNull(lastError);
    }

    /**
     * Load all dependencies (adds global/storage automatically).
     */
    public void loadAllDependencies() {
        Set<Dependency> global = this.registry.globalDependencies();
        EnumSet<Dependency> all = EnumSet.copyOf(global);

        // Add storage dependencies
        all.addAll(this.registry.storageDependencies(configurationService.storageConfig().primaryStorageType()));

        loadDependencies(all);
    }

    /**
     * Load dependencies.
     *
     * @param dependencies The dependencies
     */
    public void loadDependencies(Set<Dependency> dependencies) {
        CountDownLatch latch = new CountDownLatch(dependencies.size());

        for (Dependency dependency : dependencies) {
            threadPoolScheduler
                .async()
                .execute(() -> {
                    try {
                        loadDependency(dependency);
                    } catch (Exception e) {
                        loggingService.warn(
                            "Unable to load dependency: {0}",
                            dependency.name().toLowerCase(Locale.ENGLISH)
                        );
                        loggingService.handleException(e);
                    } finally {
                        latch.countDown();
                    }
                });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Load a single dependency.
     *
     * @param dependency The dependency
     * @throws Exception A loading exception
     */
    private void loadDependency(Dependency dependency) throws Exception {
        if (this.loaded.containsKey(dependency)) {
            return;
        }

        Path downloadFile = downloadDependency(dependency);
        if (!downloadFile.toString().contains("remapped")) {
            Path file = remapDependency(dependency, downloadFile);

            this.loaded.put(dependency, file);

            if (this.registry.shouldAutoLoad(dependency)) {
                classPathAppender.addJarToClasspath(file);
            }
        }
    }

    /**
     * Obtain a class loader.
     *
     * @param dependencies The dependencies
     * @return The classloader
     */
    public IsolatedClassLoader obtainClassLoaderWith(Set<Dependency> dependencies) {
        ImmutableSet<Dependency> set = ImmutableSet.copyOf(dependencies);

        for (Dependency dependency : dependencies) {
            if (!this.loaded.containsKey(dependency)) {
                throw new IllegalStateException("Dependency " + dependency + " is not loaded.");
            }
        }

        synchronized (this.loaders) {
            IsolatedClassLoader classLoader = this.loaders.get(set);
            if (classLoader != null) {
                return classLoader;
            }

            URL[] urls = set
                .stream()
                .map(this.loaded::get)
                .map(file -> {
                    try {
                        return file.toUri().toURL();
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);

            classLoader = new IsolatedClassLoader(urls);
            this.loaders.put(set, classLoader);
            return classLoader;
        }
    }

    /**
     * Remap the dependency.
     *
     * @param dependency The dependency
     * @param originalFile The original file
     * @return The remapped dependency path
     * @throws Exception Mapping exception
     */
    private Path remapDependency(Dependency dependency, Path originalFile) throws Exception {
        List<Relocation> rules = new ArrayList<>(dependency.relocations());
        this.registry.applyRelocationSettings(dependency, rules);

        if (rules.isEmpty()) {
            return originalFile;
        }

        Path remappedFile = this.cacheDirectory.resolve(dependency.fileName("remapped"));

        // if the remapped source exists already, just use that.
        if (Files.exists(remappedFile)) {
            return remappedFile;
        }

        relocationHandler.remap(originalFile, remappedFile, rules);

        // Delete the original download, we don't need it anymore
        Path downloadedFile = this.cacheDirectory.resolve(dependency.fileName(null));
        Files.deleteIfExists(downloadedFile);

        return remappedFile;
    }
}
