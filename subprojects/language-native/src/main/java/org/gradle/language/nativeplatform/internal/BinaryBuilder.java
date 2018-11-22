/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.language.nativeplatform.internal;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.AttributeMergingException;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.provider.Providers;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.LINKAGE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.gradle.language.nativeplatform.internal.Dimensions.createDimensionSuffix;
import static org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE;
import static org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE;

public class BinaryBuilder<T> {
    private final Project project;
    private final ImmutableAttributesFactory attributesFactory;
    private Provider<String> baseName = Providers.notDefined();
    private BinaryFactory<T> factory;

    // Dimensions
    private Collection<BuildType> buildTypes = Collections.emptySet();
    private Collection<TargetMachine> targetMachines = Collections.emptySet();
    private Collection<Linkage> linkages = Collections.emptySet();

//    private List<Dimension<?>> dimensions = Lists.newArrayList();

    public BinaryBuilder(Project project, ImmutableAttributesFactory attributesFactory) {
        this.project = project;
        this.attributesFactory = attributesFactory;
    }

//    public <I> BinaryBuilder<T> withDimension(Class<I> type, Collection<I> elements) {
//        dimensions.add(new Dimension<I>(type, elements));
//        return this;
//    }

    public BinaryBuilder<T> withBuildTypes(Collection<BuildType> buildTypes) {
        this.buildTypes = buildTypes;
        return this;
    }

    public BinaryBuilder<T> withTargetMachines(Set<TargetMachine> targetMachines) {
        this.targetMachines = targetMachines;
        return this;
    }

    public BinaryBuilder<T> withLinkages(Collection<Linkage> linkages) {
        this.linkages = linkages;
        return this;
    }

    public BinaryBuilder<T> withBaseName(Provider<String> baseName) {
        this.baseName = baseName;
        return this;
    }

    public BinaryBuilder<T> withBinaryFactory(BinaryFactory<T> factory) {
        this.factory = factory;
        return this;
    }

    public Provider<Set<T>> build() {
        return project.provider(() -> {
            Set<T> binaries = Sets.newHashSet();
            ObjectFactory objectFactory = project.getObjects();
            Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);
            Usage linkUsage = objectFactory.named(Usage.class, Usage.NATIVE_LINK);

            forEach(context -> {
                String variantName = context.getName();

                Provider<String> group = project.provider(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        return project.getGroup().toString();
                    }
                });

                Provider<String> version = project.provider(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        return project.getVersion().toString();
                    }
                });

                AttributeContainer runtimeAttributes = attributesFactory.mutable(context.getAttributes());
                runtimeAttributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);

                DefaultUsageContext runtimeUsageContext = new DefaultUsageContext(variantName + "-runtime", runtimeUsage, runtimeAttributes);

                DefaultUsageContext linkUsageContext = null;
                if (context.get(Linkage.class).isPresent()) {
                    AttributeContainer linkAttributes = attributesFactory.mutable(context.getAttributes());
                    linkAttributes.attribute(Usage.USAGE_ATTRIBUTE, linkUsage);

                    linkUsageContext = new DefaultUsageContext(variantName + "-link", linkUsage, linkAttributes);
                }

                NativeVariantIdentity variantIdentity = new NativeVariantIdentity(variantName, baseName, group, version, context.get(BuildType.class).get().isDebuggable(), context.get(BuildType.class).get().isOptimized(), context.get(TargetMachine.class).get(), linkUsageContext, runtimeUsageContext);

                if (DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(context.get(TargetMachine.class).get().getOperatingSystemFamily().getName())) {
                    binaries.add(factory.create(variantIdentity, context));
                }
            });

            return binaries;
        });
    }

    private class Dimension<T> {
        private final Class<T> type;
        private final Optional<T> value;
        private final Function<T, String> toString;
        private final ImmutableAttributes attributes;

        Dimension(Class<T> type, Optional<T> value) {
            this(type, value, it -> { throw new RuntimeException("No value"); }, ImmutableAttributes.EMPTY);

        }
        Dimension(Class<T> type, Optional<T> value, Function<T, String> toString, ImmutableAttributes attributes) {
            this.type = type;
            this.value = value;
            this.toString = toString;
            this.attributes = attributes;
        }

        public String getName() {
            if (value.isPresent()) {
                return toString.apply(value.get());
            }
            return "";
        }

        public ImmutableAttributes getAttributes() {
            return attributes;
        }
    }

    public interface DimensionContext {
        <I> Optional<I> get(Class<I> type);
    }

    private class DefaultDimensionContext implements DimensionContext{
        private final ImmutableAttributesFactory attributesFactory;
        Map<Class<?>, Dimension<?>> values = new LinkedHashMap<>();

        DefaultDimensionContext(ImmutableAttributesFactory attributesFactory) {
            this.attributesFactory = attributesFactory;
        }

        public <I> Optional<I> get(Class<I> type) {
            return Cast.uncheckedCast(values.getOrDefault(type, new Dimension<I>(type, Optional.empty())).value);
        }

        String getName() {
            StringBuilder result = new StringBuilder();
            for (Dimension<?> dimension : values.values()) {
                result.append(dimension.getName());
            }
            return StringUtils.uncapitalize(result.toString());
        }

        ImmutableAttributes getAttributes() {
            ImmutableAttributes result = ImmutableAttributes.EMPTY;
            for (Dimension<?> dimension : values.values()) {
                try {
                    result = attributesFactory.safeConcat(result, dimension.getAttributes());
                } catch (AttributeMergingException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
            return result;
        }
    }

    private void forEach(Consumer<DefaultDimensionContext> action) {
        for (BuildType buildType : buildTypes) {
            for (TargetMachine targetMachine : targetMachines) {
                for (Optional<Linkage> linkage : toOptionals(linkages)) {
                    DefaultDimensionContext context = new DefaultDimensionContext(attributesFactory);
                    context.values.put(BuildType.class, new Dimension<BuildType>(BuildType.class, Optional.of(buildType), it -> StringUtils.capitalize(it.getName()), ((AttributeContainerInternal)attributesFactory.mutable().attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable()).attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized())).asImmutable()));
                    if (linkage.isPresent()) {
                        context.values.put(Linkage.class, new Dimension<Linkage>(Linkage.class, linkage, it-> createDimensionSuffix(linkage, linkages), ((AttributeContainerInternal)attributesFactory.mutable().attribute(LINKAGE_ATTRIBUTE, linkage.get())).asImmutable()));
                    }
                    context.values.put(TargetMachine.class, new Dimension<TargetMachine>(TargetMachine.class, Optional.of(targetMachine), it -> {
                        String operatingSystemSuffix = createDimensionSuffix(targetMachine.getOperatingSystemFamily(), targetMachines);
                        String architectureSuffix = createDimensionSuffix(targetMachine.getArchitecture(), targetMachines);
                        return operatingSystemSuffix + architectureSuffix;
                    }, ((AttributeContainerInternal)attributesFactory.mutable().attribute(OPERATING_SYSTEM_ATTRIBUTE, targetMachine.getOperatingSystemFamily()).attribute(ARCHITECTURE_ATTRIBUTE, targetMachine.getArchitecture())).asImmutable()));

                    action.accept(context);
                }
            }
        }
    }

    private static <E> Collection<Optional<E>> toOptionals(Collection<E> collection) {
        if (collection.isEmpty()) {
            return ImmutableSet.of(Optional.empty());
        }
        return collection.stream().map(it -> Optional.of(it)).collect(Collectors.toSet());
    }

    public interface BinaryFactory<T> {
        T create(NativeVariantIdentity variantIdentity, DimensionContext context);
    }
}
