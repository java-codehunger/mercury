/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.platformlambda.spring.patch;

import org.springframework.beans.factory.config.YamlProcessor;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class OriginTrackedYamlLoader extends YamlProcessor {

    private final Resource resource;

    OriginTrackedYamlLoader(Resource resource) {
        this.resource = resource;
        setResources(resource);
    }

    @Override
    protected Yaml createYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setMaxAliasesForCollections(Integer.MAX_VALUE);
        loaderOptions.setAllowRecursiveKeys(true);
        return createYaml(loaderOptions);
    }

    private Yaml createYaml(LoaderOptions loaderOptions) {
        BaseConstructor constructor = new OriginTrackingConstructor(loaderOptions);
        DumperOptions dumperOptions = new DumperOptions();
        // PATCHED: 3/20/2023 - make Spring Boot 2.6.8 compatible with SnakeYaml 2.0
        Representer representer = new Representer(dumperOptions);
        LimitedResolver resolver = new LimitedResolver();
        return new Yaml(constructor, representer, dumperOptions, loaderOptions, resolver);
    }

    List<Map<String, Object>> load() {
        final List<Map<String, Object>> result = new ArrayList<>();
        process((properties, map) -> result.add(getFlattenedMap(map)));
        return result;
    }

    /**
     * {@link Constructor} that tracks property origins.
     */
    private class OriginTrackingConstructor extends SafeConstructor {

        OriginTrackingConstructor(LoaderOptions loadingConfig) {
            super(loadingConfig);
        }

        @Override
        public Object getData() throws NoSuchElementException {
            Object data = super.getData();
            if (data instanceof CharSequence && ((CharSequence) data).length() == 0) {
                return null;
            }
            return data;
        }

        @Override
        protected Object constructObject(Node node) {
            if (node instanceof CollectionNode && ((CollectionNode<?>) node).getValue().isEmpty()) {
                return constructTrackedObject(node, super.constructObject(node));
            }
            if (node instanceof ScalarNode) {
                if (!(node instanceof KeyScalarNode)) {
                    return constructTrackedObject(node, super.constructObject(node));
                }
            }
            if (node instanceof MappingNode) {
                replaceMappingNodeKeys((MappingNode) node);
            }
            return super.constructObject(node);
        }

        private void replaceMappingNodeKeys(MappingNode node) {
            node.setValue(node.getValue().stream().map(KeyScalarNode::get).collect(Collectors.toList()));
        }

        private Object constructTrackedObject(Node node, Object value) {
            Origin origin = getOrigin(node);
            return OriginTrackedValue.of(getValue(value), origin);
        }

        private Object getValue(Object value) {
            return (value != null) ? value : "";
        }

        private Origin getOrigin(Node node) {
            Mark mark = node.getStartMark();
            TextResourceOrigin.Location location = new TextResourceOrigin.Location(mark.getLine(), mark.getColumn());
            return new TextResourceOrigin(OriginTrackedYamlLoader.this.resource, location);
        }

    }

    /**
     * {@link ScalarNode} that replaces the key node in a {@link NodeTuple}.
     */
    private static class KeyScalarNode extends ScalarNode {

        KeyScalarNode(ScalarNode node) {
            super(node.getTag(), node.getValue(), node.getStartMark(), node.getEndMark(), node.getScalarStyle());
        }

        static NodeTuple get(NodeTuple nodeTuple) {
            Node keyNode = nodeTuple.getKeyNode();
            Node valueNode = nodeTuple.getValueNode();
            return new NodeTuple(KeyScalarNode.get(keyNode), valueNode);
        }

        private static Node get(Node node) {
            if (node instanceof ScalarNode) {
                return new KeyScalarNode((ScalarNode) node);
            }
            return node;
        }

    }

    /**
     * {@link Resolver} that limits {@link Tag#TIMESTAMP} tags.
     */
    private static class LimitedResolver extends Resolver {

        @Override
        public void addImplicitResolver(Tag tag, Pattern regexp, String first) {
            if (tag == Tag.TIMESTAMP) {
                return;
            }
            super.addImplicitResolver(tag, regexp, first);
        }

    }

}
