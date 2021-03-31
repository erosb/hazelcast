/*
 * Copyright (c) 2008-2021, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.config;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

public class SchemaViolationConfigurationException
        extends InvalidConfigurationException {

    private final String keywordLocation;

    private final String instanceLocation;

    private final List<SchemaViolationConfigurationException> errors;

    public SchemaViolationConfigurationException(String message, String keywordLocation,
                                                 String instanceLocation,
                                                 List<SchemaViolationConfigurationException> errors) {
        super(message);
        this.keywordLocation = keywordLocation;
        this.instanceLocation = instanceLocation;
        this.errors = unmodifiableList(new ArrayList<>(errors));
    }

    public String getKeywordLocation() {
        return keywordLocation;
    }

    public String getInstanceLocation() {
        return instanceLocation;
    }

    public List<SchemaViolationConfigurationException> getErrors() {
        return errors;
    }

    private String prettyPrint(int indentLevel) {
        String linePrefix = IntStream.range(0, indentLevel).mapToObj(i -> "  ").collect(joining(""));
        String lineSeparator = System.lineSeparator();
        StringBuilder sb = new StringBuilder(linePrefix).append(getMessage()).append(lineSeparator)
                .append(linePrefix).append("  instance location: ").append(instanceLocation).append(lineSeparator)
                .append(linePrefix).append("  keyword location: ").append(keywordLocation).append(lineSeparator);
        errors.stream().map(err -> err.prettyPrint(indentLevel + 1)).forEach(sb::append);
        return sb.toString();
    }

    @Override
    public String toString() {
        return prettyPrint(0);
    }
}
