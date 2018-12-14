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

import com.google.common.collect.Maps;
import org.gradle.api.attributes.Attribute;
import org.gradle.internal.Cast;

import java.util.Map;

public abstract class Dimension<T> {
    private final Class<T> type;
    private final T value;
    private final Map<Attribute<?>, ?> attributes = Maps.newHashMap();

    public Dimension(Class<T> type, T value) {
        this.type = type;
        this.value = value;
        initializeAttributes();
    }

    public T getValue() {
        return value;
    }

    public Class<T> getType() {
        return type;
    }

    public abstract String getName();

    public Map<Attribute<?>, ?> getAttributes() {
        return attributes;
    }

    protected <I> void attribute(Attribute<I> attribute, I value) {
        attributes.put(attribute, Cast.uncheckedCast(value));
    }

    protected abstract void initializeAttributes();
}
