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
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.internal.Cast;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class Variants {
    public static Callable<List<Variant>> of(Iterable<Provider<Collection<Dimension<?>>>> dimensions) {
        return () -> {
            List<Variant> result = new ArrayList<Variant>();
            collect(result, new Variant(), Lists.newLinkedList(dimensions));
            return result;
        };
    }

    private static void collect(List<Variant> collector, Variant context, Queue<Provider<? extends Collection<Dimension<?>>>> dimensions) {
        if (dimensions.isEmpty()) {
            collector.add(context);
        } else {
            Collection<Dimension<?>> values = dimensions.remove().get();
            values.forEach(it -> {
                collect(collector, context.put(it), Lists.newLinkedList(dimensions));
            });
        }
    }

    public static Transformer<List<NativeVariantIdentity>, List<Variant>> toVariantIdentity(Project project, Provider<String> baseName, ImmutableAttributesFactory attributesFactory) {
        return new Transformer<List<NativeVariantIdentity>, List<Variant>>() {
            @Override
            public List<NativeVariantIdentity> transform(List<Variant> variants) {
                return variants.stream().map(it -> newNativeVariantIdentity(it)).collect(Collectors.toList());
            }

            private NativeVariantIdentity newNativeVariantIdentity(Variant variant) {
                ObjectFactory objectFactory = project.getObjects();
                Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);
                Usage linkUsage = objectFactory.named(Usage.class, Usage.NATIVE_LINK);

                String variantName = variant.getName();

                Provider<String> group = project.provider(() -> project.getGroup().toString());
                Provider<String> version = project.provider(() -> project.getVersion().toString());

                AttributeContainer runtimeAttributes = attributesFactory.mutable();
                runtimeAttributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);

                variant.getAttributes().entrySet().forEach(it -> runtimeAttributes.attribute(it.getKey(), Cast.uncheckedCast(it.getValue())));

                DefaultUsageContext runtimeUsageContext = new DefaultUsageContext(variantName + "-runtime", runtimeUsage, runtimeAttributes);

                DefaultUsageContext linkUsageContext = null;
                if (variant.get(Linkage.class).isPresent()) {
                    AttributeContainer linkAttributes = attributesFactory.mutable();
                    linkAttributes.attribute(Usage.USAGE_ATTRIBUTE, linkUsage);
                    variant.getAttributes().entrySet().forEach(it -> linkAttributes.attribute(it.getKey(), Cast.uncheckedCast(it.getValue())));

                    linkUsageContext = new DefaultUsageContext(variantName + "-link", linkUsage, linkAttributes);
                }

                NativeVariantIdentity variantIdentity = new NativeVariantIdentity(variantName, baseName, group, version, variant.get(BuildType.class).get().isDebuggable(), variant.get(BuildType.class).get().isOptimized(), variant.get(TargetMachine.class).get(), linkUsageContext, runtimeUsageContext);

                return variantIdentity;
            }
        };
    }

    public static boolean isBuildable(NativeVariantIdentity identity) {
        return DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(identity.getTargetMachine().getOperatingSystemFamily().getName());
    }
}
