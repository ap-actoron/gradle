/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.internal.DefaultCppLibrary;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.language.nativeplatform.internal.Variant;
import org.gradle.language.nativeplatform.internal.Variants;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.gradle.language.nativeplatform.internal.Dimensions.buildTypeDimensions;
import static org.gradle.language.nativeplatform.internal.Dimensions.getDefaultTargetMachines;
import static org.gradle.language.nativeplatform.internal.Dimensions.linkageDimensions;
import static org.gradle.language.nativeplatform.internal.Dimensions.targetMachineDimensions;
import static org.gradle.language.nativeplatform.internal.Variants.isBuildable;
import static org.gradle.language.nativeplatform.internal.Variants.toVariantIdentity;

/**
 * <p>A plugin that produces a native library from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp`, public headers are located in `src/main/public` and implementation header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppLibrary} extension to the project to allow configuration of the library.</p>
 *
 * @since 4.1
 */
@Incubating
public class CppLibraryPlugin implements Plugin<ProjectInternal> {
    private final NativeComponentFactory componentFactory;
    private final ToolChainSelector toolChainSelector;
    private final ImmutableAttributesFactory attributesFactory;
    private final TargetMachineFactory targetMachineFactory;

    /**
     * Injects a {@link FileOperations} instance.
     *
     * @since 4.2
     */
    @Inject
    public CppLibraryPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory, TargetMachineFactory targetMachineFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
        this.targetMachineFactory = targetMachineFactory;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        final TaskContainer tasks = project.getTasks();
        final ObjectFactory objectFactory = project.getObjects();
        final ProviderFactory providers = project.getProviders();

        // Add the library and extension
        final DefaultCppLibrary library = componentFactory.newInstance(CppLibrary.class, DefaultCppLibrary.class, "main");
        project.getExtensions().add(CppLibrary.class, "library", library);
        project.getComponents().add(library);

        // Configure the component
        library.getBaseName().set(project.getName());

        library.getTargetMachines().convention(getDefaultTargetMachines(targetMachineFactory));
        library.getDevelopmentBinary().convention(project.provider(() -> {
            return library.getBinaries().get().stream()
                    .filter(binary -> binary instanceof CppSharedLibrary && !binary.isOptimized())
                    .findFirst()
                    .orElse(library.getBinaries().get().stream()
                            .filter(binary -> !library.getLinkage().get().contains(Linkage.SHARED) && !binary.isOptimized())
                            .findFirst()
                            .orElse(null));
        }));

        library.getBinaries().whenElementKnown(binary -> {
            library.getMainPublication().addVariant(binary);
        });

        Provider<List<Variant>> variants = project.provider(Variants.of(Arrays.asList(project.provider(buildTypeDimensions()), project.provider(linkageDimensions(library.getLinkage())), project.provider(targetMachineDimensions(library.getTargetMachines())))));

        Provider<List<NativeVariantIdentity>> identities = variants.map(toVariantIdentity(project, library.getBaseName(), attributesFactory));

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                library.getBinaries().addAll(identities.map(createBinaries(library)));

                identities.get().stream().filter(it -> !isBuildable(it)).forEach(variantIdentity -> {
                    // Known, but not buildable
                    library.getMainPublication().addVariant(variantIdentity);
                });

                final Configuration apiElements = library.getApiElements();
                // TODO - deal with more than one header dir, e.g. generated public headers
                Provider<File> publicHeaders = providers.provider(new Callable<File>() {
                    @Override
                    public File call() throws Exception {
                        Set<File> files = library.getPublicHeaderDirs().getFiles();
                        if (files.size() != 1) {
                            throw new UnsupportedOperationException(String.format("The C++ library plugin currently requires exactly one public header directory, however there are %d directories configured: %s", files.size(), files));
                        }
                        return files.iterator().next();
                    }
                });
                apiElements.getOutgoing().artifact(publicHeaders);

                project.getPluginManager().withPlugin("maven-publish", new Action<AppliedPlugin>() {
                    @Override
                    public void execute(AppliedPlugin appliedPlugin) {
                        final TaskProvider<Zip> headersZip = tasks.register("cppHeaders", Zip.class, new Action<Zip>() {
                            @Override
                            public void execute(Zip headersZip) {
                                headersZip.from(library.getPublicHeaderFiles());
                                headersZip.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("headers"));
                                headersZip.getArchiveClassifier().set("cpp-api-headers");
                                headersZip.getArchiveFileName().set("cpp-api-headers.zip");
                            }
                        });
                        library.getMainPublication().addArtifact(new LazyPublishArtifact(headersZip));
                    }
                });

                library.getBinaries().realizeNow();
            }
        });
    }

    private Transformer<List<CppBinary>, List<NativeVariantIdentity>> createBinaries(DefaultCppLibrary component) {
        return new Transformer<List<CppBinary>, List<NativeVariantIdentity>>() {
            @Override
            public List<CppBinary> transform(List<NativeVariantIdentity> identities) {
                return identities.stream().filter(Variants::isBuildable).map(identity -> {
                    ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class, identity.getTargetMachine());

                    if (identity.getLinkage().equals(Linkage.SHARED)) {
                        return component.addSharedLibrary(identity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                    } else if (identity.getLinkage().equals(Linkage.STATIC)) {
                        return component.addStaticLibrary(identity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                    }
                    throw new IllegalArgumentException("Invalid linkage");
                }).collect(Collectors.toList());
            }
        };
    }

    private boolean shouldPrefer(BuildType buildType, TargetMachine targetMachine, CppLibrary library) {
        return buildType == BuildType.DEBUG && (targetMachine.getArchitecture().equals(((DefaultTargetMachineFactory)targetMachineFactory).host().getArchitecture()) || !library.getDevelopmentBinary().isPresent());
    }
}
