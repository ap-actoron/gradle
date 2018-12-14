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

import org.apache.commons.lang.StringUtils;
import org.gradle.api.Named;
import org.gradle.api.provider.SetProperty;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.LINKAGE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE;
import static org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE;

public class Dimensions<T> {
    public static String createDimensionSuffix(Named dimensionValue, Collection<?> multivalueProperty) {
        return createDimensionSuffix(dimensionValue.getName(), multivalueProperty);
    }

    public static String createDimensionSuffix(String dimensionValue, Collection<?> multivalueProperty) {
        if (isDimensionVisible(multivalueProperty)) {
            return StringUtils.capitalize(dimensionValue.toLowerCase());
        }
        return "";
    }

    public static String createDimensionSuffix(Optional<? extends Named> dimensionValue, Collection<?> multivalueProperty) {
        if (dimensionValue.isPresent() && isDimensionVisible(multivalueProperty)) {
            return StringUtils.capitalize(dimensionValue.get().getName().toLowerCase());
        }
        return "";
    }

    public static boolean isDimensionVisible(Collection<?> multivalueProperty) {
        return multivalueProperty.size() > 1;
    }

    public static Callable<Collection<Dimension<?>>> buildTypeDimensions() {
        return buildTypeDimensions(BuildType.DEFAULT_BUILD_TYPES);
    }

    public static Callable<Collection<Dimension<?>>> buildTypeDimensions(Collection<BuildType> buildTypes) {
        return () -> {
            return buildTypes.stream().map(it -> new Dimension<BuildType>(BuildType.class, it) {
                @Override
                public String getName() {
                    return createDimensionSuffix(it, buildTypes);
                }

                @Override
                protected void initializeAttributes() {
                    attribute(DEBUGGABLE_ATTRIBUTE, getValue().isDebuggable());
                    attribute(OPTIMIZED_ATTRIBUTE, getValue().isOptimized());
                }
            }).collect(Collectors.toList());
        };
    }

    public static Callable<Collection<Dimension<?>>> buildTypeDimensions(BuildType buildType) {
        return buildTypeDimensions(Arrays.asList(buildType));
    }

    public static Callable<Collection<Dimension<?>>> targetMachineDimensions(SetProperty<TargetMachine> targetMachines) {
        return () -> {
            Set<TargetMachine> values = targetMachines.get();
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A target machine needs to be specified for the component.");
            }
            targetMachines.finalizeValue();

            return values.stream().map(it -> new Dimension<TargetMachine>(TargetMachine.class, it) {
                @Override
                public String getName() {
                    String operatingSystemSuffix = createDimensionSuffix(it.getOperatingSystemFamily(), values.stream().map(TargetMachine::getOperatingSystemFamily).distinct().collect(Collectors.toList()));
                    String architectureSuffix = createDimensionSuffix(it.getArchitecture(), values.stream().map(TargetMachine::getArchitecture).distinct().collect(Collectors.toList()));
                    return operatingSystemSuffix + architectureSuffix;
                }

                @Override
                protected void initializeAttributes() {
                    attribute(ARCHITECTURE_ATTRIBUTE, getValue().getArchitecture());
                    attribute(OPERATING_SYSTEM_ATTRIBUTE, getValue().getOperatingSystemFamily());
                }
            }).collect(Collectors.toList());
        };
    }

    public static Callable<Collection<Dimension<?>>> linkageDimensions(SetProperty<Linkage> linkages) {
        return () -> {
            Set<Linkage> values = linkages.get();
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A linkage needs to be specified for the component.");
            }
            linkages.finalizeValue();

            return values.stream().map(it -> new Dimension<Linkage>(Linkage.class, it) {
                @Override
                public String getName() {
                    return createDimensionSuffix(it, values);
                }

                @Override
                protected void initializeAttributes() {
                    attribute(LINKAGE_ATTRIBUTE, it);
                }
            }).collect(Collectors.toList());
        };
    }

    /**
     * Used by all native plugins to work around the missing default feature on Property
     *
     * See https://github.com/gradle/gradle-native/issues/918
     *
     * @since 5.1
     */
    public static Set<TargetMachine> getDefaultTargetMachines(TargetMachineFactory targetMachineFactory) {
        return Collections.singleton(((DefaultTargetMachineFactory)targetMachineFactory).host());
    }
}
