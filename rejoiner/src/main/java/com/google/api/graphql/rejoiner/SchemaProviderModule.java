// Copyright 2017 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.api.graphql.rejoiner;

import static graphql.schema.GraphQLObjectType.newObject;

import com.google.common.collect.Lists;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.protobuf.Descriptors.FileDescriptor;
import graphql.relay.Relay;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.SchemaUtil;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;

/** Provides a {@link GraphQLSchema} by combining fields from all SchemaModules. */
public final class SchemaProviderModule extends AbstractModule {

  static class SchemaImpl implements Provider<GraphQLSchema> {
    private final Set<GraphQLFieldDefinition> queryFields;
    private final Set<GraphQLFieldDefinition> mutationFields;
    private final Set<TypeModification> modifications;
    private final Set<FileDescriptor> fileDescriptors;
    private final Set<NodeDataFetcher> nodeDataFetchers;

    @Inject
    public SchemaImpl(
        @Annotations.Queries Set<GraphQLFieldDefinition> queryFields,
        @Annotations.Mutations Set<GraphQLFieldDefinition> mutationFields,
        @Annotations.GraphModifications Set<TypeModification> modifications,
        @Annotations.ExtraTypes Set<FileDescriptor> fileDescriptors,
        @Annotations.Queries Set<NodeDataFetcher> nodeDataFetchers) {
      this.queryFields = queryFields;
      this.mutationFields = mutationFields;
      this.modifications = modifications;
      this.fileDescriptors = fileDescriptors;
      this.nodeDataFetchers = nodeDataFetchers;
    }

    @Override
    public GraphQLSchema get() {
      Map<String, ? extends Function<String, Object>> nodeDataFetchers =
          this.nodeDataFetchers
              .stream()
              .collect(Collectors.toMap(e -> e.getClassName(), Function.identity()));

      Map<String, String> oldNameToNewNameMapping = modifications.stream()
        .filter(mod -> mod instanceof Type.RenameType)
        .map(mod -> (Type.RenameType) mod)
        .collect(Collectors.toMap(mod -> mod.getTypeName(), mod -> mod.getNewTypeName()));
      Set<GraphQLFieldDefinition> renamedQueryFields = queryFields.stream().map(fd -> {
        if (fd.getType() instanceof GraphQLTypeReference) {
          String name = fd.getType().getName();
          // found in new name old name mapping
          if (oldNameToNewNameMapping.containsKey(name)) {
            String newName = oldNameToNewNameMapping.get(name);
            return GraphQLFieldDefinition.newFieldDefinition(fd).type(new GraphQLTypeReference(newName)).build();
          } else {
            return fd;
          }
        } else {
          return fd;
        }
      }).collect(Collectors.toSet());

      GraphQLObjectType.Builder queryType =
          newObject().name("QueryType").fields(Lists.newArrayList(renamedQueryFields));

      ProtoRegistry protoRegistry =
          ProtoRegistry.newBuilder().addAll(fileDescriptors).add(modifications).build();

      if (protoRegistry.hasRelayNode()) {
        queryType.field(
            new Relay()
                .nodeField(
                    protoRegistry.getRelayNode(),
                    environment -> {
                      String id = environment.getArgument("id");
                      Relay.ResolvedGlobalId resolvedGlobalId = new Relay().fromGlobalId(id);
                      Function<String, ?> stringFunction =
                          nodeDataFetchers.get(resolvedGlobalId.getType());
                      if (stringFunction == null) {
                        throw new RuntimeException(
                            String.format(
                                "Relay Node fetcher not implemented for type=%s",
                                resolvedGlobalId.getType()));
                      }
                      return stringFunction.apply(resolvedGlobalId.getId());
                    }));
      }

      if (mutationFields.isEmpty()) {
        return GraphQLSchema.newSchema().query(queryType).build(protoRegistry.listTypes());
      }
      GraphQLObjectType mutationType =
          newObject().name("MutationType").fields(Lists.newArrayList(mutationFields)).build();
      return GraphQLSchema.newSchema()
          .query(queryType)
          .mutation(mutationType)
          .build(protoRegistry.listTypes());
    }
  }

  @Override
  protected void configure() {
    bind(GraphQLSchema.class)
      .annotatedWith(Schema.class)
      .toProvider(SchemaImpl.class)
      .in(Singleton.class);
  }
}
