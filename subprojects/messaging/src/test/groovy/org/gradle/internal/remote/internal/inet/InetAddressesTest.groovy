/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.internal.remote.internal.inet

import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Specification

class InetAddressesTest extends Specification{
    def addresses = new InetAddresses()

    def "always contains at least one loopback address"() {
        expect:
        !addresses.loopback.empty
    }

    @Requires(TestPrecondition.ONLINE)
    def "always contains at least one remote address"() {
        expect:
        !addresses.remote.empty
    }

    def "No remote addresses are loopback addresses"() {
        expect:
        addresses.remote.intersect(addresses.loopback).empty
    }
}
