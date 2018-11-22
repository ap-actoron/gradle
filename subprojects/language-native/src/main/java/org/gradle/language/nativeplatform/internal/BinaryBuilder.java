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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.attributes.Attribute;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.gradle.language.nativeplatform.internal.Dimensions.createDimensionSuffix;

public class BinaryBuilder<T> {
    private final Project project;
    private final ImmutableAttributesFactory attributesFactory;
    private Provider<String> baseName = Providers.notDefined();
    private BinaryFactory<T> factory;
    private List<DimensionValues<?>> dimensions = Lists.newArrayList();

    public BinaryBuilder(Project project, ImmutableAttributesFactory attributesFactory) {
        this.project = project;
        this.attributesFactory = attributesFactory;
    }

    public <I> BinaryBuilder<T> withDimension(DimensionValues<I> dimension) {
        dimensions.add(dimension);
        return this;
    }

    public static <I> DimensionBuilder<I> newDimension(Class<I> type) {
        return new DimensionBuilder<>(type);
    }

    public static class DimensionBuilder<I> {
        private final Class<I> type;
        private Collection<I> values;
        private Function<I, String> toStringSupplier = it -> {
            if (it instanceof Named) {
                return createDimensionSuffix((Named) it, values);
            }
            throw new IllegalArgumentException("Can't to string without name");
        };
        private final Map<Attribute<Object>, Function<I, Object>> attributes = new HashMap<>();

        public DimensionBuilder(Class<I> type) {
            this.type = type;
        }

        public DimensionBuilder<I> withValues(Collection<I> values) {
            this.values = values;
            return this;
        }

        public <U> DimensionBuilder<I> attribute(Attribute<U> key, Function<I, U> supplier) {
            attributes.put(Cast.uncheckedCast(key), Cast.uncheckedCast(supplier));
            return this;
        }

        public DimensionBuilder<I> withName(Function<I, String> supplier) {
            toStringSupplier = supplier;
            return this;
        }

        public DimensionValues<I> build() {
            return new DimensionValues<I>(type, values, toStringSupplier, attributes);
        }
    }

    public BinaryBuilder<T> withBaseName(Provider<String> baseName) {
        this.baseName = baseName;
        return this;
    }

    public BinaryBuilder<T> withBinaryFactory(BinaryFactory<T> factory) {
        this.factory = factory;
        return this;
    }

    public class Result {
        public Provider<? extends Set<T>> getBinaries() {
            return project.provider(() -> {
                Set<T> binaries = Sets.newHashSet();

                forEach(context -> {
                    NativeVariantIdentity variantIdentity = newVariantIdentity(context);

                    if (DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(context.get(TargetMachine.class).get().getOperatingSystemFamily().getName())) {
                        binaries.add(factory.create(variantIdentity, context));
                    }
                });

                return binaries;
            });
        }

        public Provider<? extends Set<NativeVariantIdentity>> getNonBuildableVariants() {
            return project.provider(() -> {
                Set<NativeVariantIdentity> variantIdentities = Sets.newHashSet();

                forEach(context -> {
                    NativeVariantIdentity variantIdentity = newVariantIdentity(context);

                    if (DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(context.get(TargetMachine.class).get().getOperatingSystemFamily().getName())) {
                        // Do nothing...
                    } else {
                        variantIdentities.add(variantIdentity);
                    }
                });

                return variantIdentities;
            });
        }

        private NativeVariantIdentity newVariantIdentity(DefaultDimensionContext context) {
            ObjectFactory objectFactory = project.getObjects();
            Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);
            Usage linkUsage = objectFactory.named(Usage.class, Usage.NATIVE_LINK);

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

            return variantIdentity;
        }
    }

    public Result build() {
        return new Result();
    }

    private static class DimensionValues<I> {
        private final Class<I> type;
        private final Collection<I> values;
        private final Function<I, String> toStringSupplier;
        private final Map<Attribute<Object>, Function<I, Object>> attributes;

        DimensionValues(Class<I> type, Collection<I> values, Function<I, String> toStringSupplier, Map<Attribute<Object>, Function<I, Object>> attributes) {
            this.type = type;
            this.values = values;
            this.toStringSupplier = toStringSupplier;
            this.attributes = attributes;
        }

        void forEach(DefaultDimensionContext context, Queue<DimensionValues<?>> dimensions, Consumer<DefaultDimensionContext> action) {
            if (values.isEmpty()) {
                BinaryBuilder.forEach(context, dimensions, action);
            } else {
                values.stream().map(it -> Optional.of(it)).forEach(it -> {
                    context.values.put(type, new Dimension<I>(type, it, toStringSupplier, attributes));
                    BinaryBuilder.forEach(context, dimensions, action);
                });
            }
        }
    }

    private static class Dimension<I> {
        private final Class<I> type;
        private final Optional<I> value;
        private final Function<I, String> toString;
        private final Map<Attribute<Object>, Function<I, Object>> attributes;

        Dimension(Class<I> type, Optional<I> value) {
            this(type, value, it -> { throw new RuntimeException("No value"); }, Maps.newHashMap());

        }
        Dimension(Class<I> type, Optional<I> value, Function<I, String> toString, Map<Attribute<Object>, Function<I, Object>> attributes) {
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

        public Map<Attribute<Object>, Object> getAttributes() {
            return attributes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, it -> it.getValue().apply(value.get())));
        }
    }

    public interface DimensionContext {
        <I> Optional<I> get(Class<I> type);
    }

    private static class DefaultDimensionContext implements DimensionContext{
        private final ImmutableAttributesFactory attributesFactory;
        Map<Class<?>, Dimension<?>> values = new LinkedHashMap<>();

        DefaultDimensionContext(ImmutableAttributesFactory attributesFactory) {
            this.attributesFactory = attributesFactory;
        }

        public <I> Optional<I> get(Class<I> type) {
            if (values.containsKey(type)) {
                return Cast.uncheckedCast(values.get(type).value);
            }
            return Optional.empty();
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
                    result = attributesFactory.safeConcat(result, toAttributes(dimension.getAttributes()));
                } catch (AttributeMergingException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
            return result;
        }

        private ImmutableAttributes toAttributes(Map<Attribute<Object>, Object> attributes) {
            AttributeContainerInternal result = attributesFactory.mutable();
            attributes.entrySet().forEach(it -> {
                result.attribute(it.getKey(), it.getValue());
            });
            return result.asImmutable();
        }
    }

    private static void forEach(DefaultDimensionContext context, Queue<DimensionValues<?>> dimensions, Consumer<DefaultDimensionContext> action) {
        if (dimensions.isEmpty()) {
            action.accept(context);
        } else {
            dimensions.remove().forEach(context, new LinkedList<>(dimensions), action);
        }
    }

    private void forEach(Consumer<DefaultDimensionContext> action) {
        DefaultDimensionContext context = new DefaultDimensionContext(attributesFactory);
        forEach(context, new LinkedList<>(dimensions), action);
    }

    public interface BinaryFactory<T> {
        T create(NativeVariantIdentity variantIdentity, DimensionContext context);
    }
}
