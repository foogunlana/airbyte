/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.util.MoreIterators;
import io.airbyte.integrations.base.AirbyteStreamNameNamespacePair;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.StandardNameTransformer;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigQueryDenormalizedRecordConsumer extends BigQueryRecordConsumer {

  private static final Logger LOGGER = LoggerFactory.getLogger(BigQueryDenormalizedRecordConsumer.class);

  private final StandardNameTransformer namingResolver;
  private final Set<String> invalidKeys;

  public BigQueryDenormalizedRecordConsumer(final BigQuery bigquery,
                                            final Map<AirbyteStreamNameNamespacePair, BigQueryWriteConfig> writeConfigs,
                                            final ConfiguredAirbyteCatalog catalog,
                                            final Consumer<AirbyteMessage> outputRecordCollector,
                                            final StandardNameTransformer namingResolver) {
    super(bigquery, writeConfigs, catalog, outputRecordCollector, false, false);
    this.namingResolver = namingResolver;
    invalidKeys = new HashSet<>();
  }

  @Override
  protected JsonNode formatRecord(final Schema schema, final AirbyteRecordMessage recordMessage) {
    // Bigquery represents TIMESTAMP to the microsecond precision, so we convert to microseconds then
    // use BQ helpers to string-format correctly.
    final long emittedAtMicroseconds = TimeUnit.MICROSECONDS.convert(recordMessage.getEmittedAt(), TimeUnit.MILLISECONDS);
    final String formattedEmittedAt = QueryParameterValue.timestamp(emittedAtMicroseconds).getValue();
    Preconditions.checkArgument(recordMessage.getData().isObject());
    final ObjectNode data = (ObjectNode) formatData(schema.getFields(), recordMessage.getData());
    data.put(JavaBaseConstants.COLUMN_NAME_AB_ID, UUID.randomUUID().toString());
    data.put(JavaBaseConstants.COLUMN_NAME_EMITTED_AT, formattedEmittedAt);
    return data;
  }

  protected JsonNode formatData(final FieldList fields, final JsonNode root) {
    // handles empty objects and arrays
    if (fields == null) {
      return root;
    }
    if (root.isObject()) {
      final List<String> fieldNames = fields.stream().map(Field::getName).collect(Collectors.toList());
      return Jsons.jsonNode(Jsons.keys(root).stream()
          .filter(key -> {
            final boolean validKey = fieldNames.contains(namingResolver.getIdentifier(key));
            if (!validKey && !invalidKeys.contains(key)) {
              LOGGER.warn("Ignoring field {} as it is not defined in catalog", key);
              invalidKeys.add(key);
            }
            return validKey;
          })
          .collect(Collectors.toMap(namingResolver::getIdentifier,
              key -> formatData(fields.get(namingResolver.getIdentifier(key)).getSubFields(), root.get(key)))));
    } else if (root.isArray()) {
      // Arrays can have only one field
      final Field arrayField = fields.get(0);
      // If an array of records, we should use subfields
      final FieldList subFields = (arrayField.getSubFields() == null || arrayField.getSubFields().isEmpty() ? fields : arrayField.getSubFields());
      final JsonNode items = Jsons.jsonNode(MoreIterators.toList(root.elements()).stream()
          .map(p -> formatData(subFields, p))
          .collect(Collectors.toList()));

      // "Array of Array of" (nested arrays) are not permitted by BigQuery ("Array of Record of Array of"
      // is). Turn all "Array of" into "Array of Record of" instead
      return Jsons.jsonNode(ImmutableMap.of(BigQueryDenormalizedDestination.NESTED_ARRAY_FIELD, items));
    } else {
      return root;
    }
  }

}
