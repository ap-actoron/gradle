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
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppExecutable;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.internal.DefaultCppApplication;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.Variant;
import org.gradle.language.nativeplatform.internal.Variants;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.gradle.language.nativeplatform.internal.Dimensions.buildTypeDimensions;
import static org.gradle.language.nativeplatform.internal.Dimensions.getDefaultTargetMachines;
import static org.gradle.language.nativeplatform.internal.Dimensions.targetMachineDimensions;
import static org.gradle.language.nativeplatform.internal.Variants.isBuildable;
import static org.gradle.language.nativeplatform.internal.Variants.toVariantIdentity;

/**
 * <p>A plugin that produces a native application from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp` and header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppApplication} extension to the project to allow configuration of the application.</p>
 *
 * @since 4.5
 */
@Incubating
public class CppApplicationPlugin implements Plugin<ProjectInternal> {
    private final NativeComponentFactory componentFactory;
    private final ToolChainSelector toolChainSelector;
    private final ImmutableAttributesFactory attributesFactory;
    private final TargetMachineFactory targetMachineFactory;
    private final ProviderFactory providerFactory;

    @Inject
    public CppApplicationPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory, TargetMachineFactory targetMachineFactory, ProviderFactory providerFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
        this.targetMachineFactory = targetMachineFactory;
        this.providerFactory = providerFactory;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        final ObjectFactory objectFactory = project.getObjects();

        // Add the application and extension
        final DefaultCppApplication application = componentFactory.newInstance(CppApplication.class, DefaultCppApplication.class, "main");
        project.getExtensions().add(CppApplication.class, "application", application);
        project.getComponents().add(application);

        // Configure the component
        application.getBaseName().set(project.getName());

        application.getTargetMachines().convention(getDefaultTargetMachines(targetMachineFactory));
        application.getBinaries().whenElementKnown(CppExecutable.class, binary -> {
            // Use the debug variant as the development binary
            // Prefer the host architecture, if present, else use the first architecture specified
            if (!binary.isOptimized() && (binary.getTargetPlatform().getArchitecture().equals(((DefaultTargetMachineFactory)targetMachineFactory).host().getArchitecture()) || !application.getDevelopmentBinary().isPresent())) {
                application.getDevelopmentBinary().set(binary);
            }
        });

        application.getBinaries().whenElementKnown(binary -> {
            application.getMainPublication().addVariant(binary);
        });

        Provider<List<Variant>> variants = project.provider(Variants.of(Arrays.asList(project.provider(buildTypeDimensions()), project.provider(targetMachineDimensions(application.getTargetMachines())))));

        Provider<List<NativeVariantIdentity>> identities = variants.map(toVariantIdentity(project, application.getBaseName(), attributesFactory));

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                application.getBinaries().addAll(identities.map(createBinaries(application)));

                identities.get().stream().filter(it -> !isBuildable(it)).forEach(variantIdentity -> {
                    // Known, but not buildable
                    application.getMainPublication().addVariant(variantIdentity);
                });

                // Configure the binaries
                application.getBinaries().realizeNow();
            }
        });
    }

    private Transformer<List<CppBinary>, List<NativeVariantIdentity>> createBinaries(DefaultCppApplication component) {
        return new Transformer<List<CppBinary>, List<NativeVariantIdentity>>() {
            @Override
            public List<CppBinary> transform(List<NativeVariantIdentity> identities) {
                return identities.stream().filter(Variants::isBuildable).map(identity -> {
                    ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class, identity.getTargetMachine());
                    return component.addExecutable(identity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                }).collect(Collectors.toList());
            }
        };
    }
}
