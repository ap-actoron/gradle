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
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppExecutable;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.internal.DefaultCppApplication;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.BinaryBuilder;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory;

import javax.inject.Inject;
import java.util.Set;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.gradle.language.nativeplatform.internal.Dimensions.createDimensionSuffix;
import static org.gradle.language.nativeplatform.internal.Dimensions.getDefaultTargetMachines;
import static org.gradle.language.plugins.NativeBasePlugin.setDefaultAndGetTargetMachineValues;
import static org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE;
import static org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE;

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

    @Inject
    public CppApplicationPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory, TargetMachineFactory targetMachineFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
        this.targetMachineFactory = targetMachineFactory;
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

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                Set<TargetMachine> targetMachines = setDefaultAndGetTargetMachineValues(application.getTargetMachines(), targetMachineFactory);
                if (targetMachines.isEmpty()) {
                    throw new IllegalArgumentException("A target machine needs to be specified for the application.");
                }

                BinaryBuilder.Result binaryResult = new BinaryBuilder<CppExecutable>(project, attributesFactory)
                        .withDimension(
                                BinaryBuilder.newDimension(BuildType.class)
                                        .withValues(BuildType.DEFAULT_BUILD_TYPES)
                                        .attribute(DEBUGGABLE_ATTRIBUTE, it -> it.isDebuggable())
                                        .attribute(OPTIMIZED_ATTRIBUTE, it -> it.isOptimized())
                                        .build())
                        .withDimension(
                                BinaryBuilder.newDimension(TargetMachine.class)
                                        .withValues(targetMachines)
                                        .attribute(OPERATING_SYSTEM_ATTRIBUTE, it -> it.getOperatingSystemFamily())
                                        .attribute(ARCHITECTURE_ATTRIBUTE, it -> it.getArchitecture())
                                        .withName(it -> {
                                            String operatingSystemSuffix = createDimensionSuffix(it.getOperatingSystemFamily(), targetMachines);
                                            String architectureSuffix = createDimensionSuffix(it.getArchitecture(), targetMachines);
                                            return operatingSystemSuffix + architectureSuffix;
                                        })
                                        .build())
                        .withBaseName(application.getBaseName())
                        .withBinaryFactory((NativeVariantIdentity variantIdentity, BinaryBuilder.DimensionContext context) -> {
                            ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class, context.get(TargetMachine.class).get());
                            return application.addExecutable(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                        })
                        .build();
                application.getBinaries().addAll(binaryResult.getBinaries());
                ((Set<NativeVariantIdentity>)binaryResult.getNonBuildableVariants().get()).forEach(variantIdentity -> {
                    // Known, but not buildable
                    application.getMainPublication().addVariant(variantIdentity);
                });

                // Configure the binaries
                application.getBinaries().realizeNow();
            }
        });
    }
}
