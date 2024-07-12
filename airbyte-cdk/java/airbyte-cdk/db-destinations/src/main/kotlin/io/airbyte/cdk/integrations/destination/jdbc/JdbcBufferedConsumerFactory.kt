/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.cdk.integrations.destination.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.base.Preconditions
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.base.SerializedAirbyteMessageConsumer
import io.airbyte.cdk.integrations.destination.NamingConventionTransformer
import io.airbyte.cdk.integrations.destination.StreamSyncSummary
import io.airbyte.cdk.integrations.destination.async.AsyncStreamConsumer
import io.airbyte.cdk.integrations.destination.async.buffers.BufferManager
import io.airbyte.cdk.integrations.destination.async.deser.AirbyteMessageDeserializer
import io.airbyte.cdk.integrations.destination.async.deser.IdentityDataTransformer
import io.airbyte.cdk.integrations.destination.async.deser.StreamAwareDataTransformer
import io.airbyte.cdk.integrations.destination.async.model.PartialAirbyteMessage
import io.airbyte.cdk.integrations.destination.async.state.FlushFailure
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnCloseFunction
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnStartFunction
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.RecordWriter
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.base.destination.typing_deduping.ParsedCatalog
import io.airbyte.integrations.base.destination.typing_deduping.StreamConfig
import io.airbyte.integrations.base.destination.typing_deduping.TyperDeduper
import io.airbyte.protocol.models.v0.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.Function

private val LOGGER = KotlinLogging.logger {}
/**
 * Strategy:
 *
 * 1. Create a final table for each stream
 *
 * 2. Accumulate records in a buffer. One buffer per stream
 *
 * 3. As records accumulate write them in batch to the database. We set a minimum numbers of records
 * before writing to avoid wasteful record-wise writes. In the case with slow syncs this will be
 * superseded with a periodic record flush from [BufferedStreamConsumer.periodicBufferFlush]
 *
 * 4. Once all records have been written to buffer, flush the buffer and write any remaining records
 * to the database (regardless of how few are left)
 */
object JdbcBufferedConsumerFactory {

    const val DEFAULT_OPTIMAL_BATCH_SIZE_FOR_FLUSH = 25 * 1024 * 1024L

    /** @param parsedCatalog Nullable for v1 destinations. Required for v2 destinations. */
    fun createAsync(
        outputRecordCollector: Consumer<AirbyteMessage>,
        database: JdbcDatabase,
        sqlOperations: SqlOperations,
        namingResolver: NamingConventionTransformer,
        config: JsonNode,
        catalog: ConfiguredAirbyteCatalog,
        defaultNamespace: String,
        typerDeduper: TyperDeduper,
        dataTransformer: StreamAwareDataTransformer = IdentityDataTransformer(),
        optimalBatchSizeBytes: Long = DEFAULT_OPTIMAL_BATCH_SIZE_FOR_FLUSH,
        parsedCatalog: ParsedCatalog,
    ): SerializedAirbyteMessageConsumer {
        val writeConfigs =
            createWriteConfigs(
                namingResolver,
                config,
                sqlOperations.isSchemaRequired,
                parsedCatalog
            )
        return AsyncStreamConsumer(
            outputRecordCollector,
            onStartFunction(database, sqlOperations, writeConfigs, typerDeduper),
            onCloseFunction(typerDeduper),
            JdbcInsertFlushFunction(
                recordWriterFunction(database, sqlOperations, writeConfigs, catalog),
                optimalBatchSizeBytes
            ),
            catalog,
            BufferManager(defaultNamespace, (Runtime.getRuntime().maxMemory() * 0.2).toLong()),
            FlushFailure(),
            Executors.newFixedThreadPool(2),
            AirbyteMessageDeserializer(dataTransformer)
        )
    }

    private fun createWriteConfigs(
        namingResolver: NamingConventionTransformer,
        config: JsonNode,
        schemaRequired: Boolean,
        parsedCatalog: ParsedCatalog,
    ): List<WriteConfig> {
        if (schemaRequired) {
            Preconditions.checkState(
                config.has("schema"),
                "jdbc destinations must specify a schema."
            )
        }
        return parsedCatalog.streams
            .map { parsedStreamToWriteConfig(namingResolver, "").apply(it) }
            .toList()
    }

    private fun parsedStreamToWriteConfig(
        namingResolver: NamingConventionTransformer,
        rawTableSuffix: String,
    ): Function<StreamConfig, WriteConfig> {
        return Function { streamConfig: StreamConfig ->
            // TODO We should probably replace WriteConfig with StreamConfig?
            // The only thing I'm not sure about is the tmpTableName thing,
            // but otherwise it's a strict improvement (avoids people accidentally
            // recomputing the table names, instead of just treating the output of
            // CatalogParser as canonical).
            WriteConfig(
                streamConfig.id.originalName,
                streamConfig.id.originalNamespace,
                streamConfig.id.rawNamespace,
                @Suppress("deprecation")
                namingResolver.getTmpTableName(streamConfig.id.rawNamespace),
                streamConfig.id.rawName,
                streamConfig.postImportAction,
                streamConfig.syncId,
                streamConfig.generationId,
                streamConfig.minimumGenerationId,
                rawTableSuffix
            )
        }
    }

    /**
     * Sets up destination storage through:
     *
     * 1. Creates Schema (if not exists)
     *
     * 2. Creates airybte_raw table (if not exists)
     *
     * 3. <Optional>Truncates table if sync mode is in OVERWRITE
     *
     * @param database JDBC database to connect to
     * @param sqlOperations interface for execution SQL queries
     * @param writeConfigs settings for each stream </Optional>
     */
    private fun onStartFunction(
        database: JdbcDatabase,
        sqlOperations: SqlOperations,
        writeConfigs: Collection<WriteConfig>,
        typerDeduper: TyperDeduper
    ): OnStartFunction {
        return OnStartFunction {
            typerDeduper.prepareSchemasAndRunMigrations()
            LOGGER.info {
                "Preparing raw tables in destination started for ${writeConfigs.size} streams"
            }
            val queryList: MutableList<String> = ArrayList()
            for (writeConfig in writeConfigs) {
                val schemaName = writeConfig.rawNamespace
                val dstTableName = writeConfig.rawTableName
                LOGGER.info {
                    "Preparing raw table in destination started for stream ${writeConfig.streamName}. schema: $schemaName, table name: $dstTableName"
                }
                sqlOperations.createSchemaIfNotExists(database, schemaName)
                sqlOperations.createTableIfNotExists(database, schemaName, dstTableName)
                when (writeConfig.minimumGenerationId) {
                    writeConfig.generationId ->
                        queryList.add(
                            sqlOperations.truncateTableQuery(database, schemaName, dstTableName)
                        )
                    0L -> {}
                    else ->
                        throw IllegalStateException(
                            "Invalid minimumGenerationId ${writeConfig.minimumGenerationId} for stream ${writeConfig.streamName}. generationId=${writeConfig.generationId}"
                        )
                }
            }
            sqlOperations.executeTransaction(database, queryList)
            LOGGER.info { "Preparing raw tables in destination completed." }
            typerDeduper.prepareFinalTables()
        }
    }

    /**
     * Writes [AirbyteRecordMessage] to JDBC database's airbyte_raw table
     *
     * @param database JDBC database to connect to
     * @param sqlOperations interface of SQL queries to execute
     * @param writeConfigs settings for each stream
     * @param catalog catalog of all streams to sync
     */
    private fun recordWriterFunction(
        database: JdbcDatabase,
        sqlOperations: SqlOperations,
        writeConfigs: List<WriteConfig>,
        catalog: ConfiguredAirbyteCatalog,
    ): RecordWriter<PartialAirbyteMessage> {
        val pairToWriteConfig: Map<AirbyteStreamNameNamespacePair, WriteConfig> =
            writeConfigs.associateBy { toNameNamespacePair(it) }

        return RecordWriter {
            pair: AirbyteStreamNameNamespacePair,
            records: List<PartialAirbyteMessage> ->
            require(pairToWriteConfig.containsKey(pair)) {
                String.format(
                    "Message contained record from a stream that was not in the catalog. \ncatalog: %s, \nstream identifier: %s\nkeys: %s",
                    Jsons.serialize(catalog),
                    pair,
                    pairToWriteConfig.keys
                )
            }
            val writeConfig = pairToWriteConfig.getValue(pair)
            sqlOperations.insertRecords(
                database,
                ArrayList(records),
                writeConfig.rawNamespace,
                writeConfig.rawTableName
            )
        }
    }

    /** Tear down functionality */
    private fun onCloseFunction(typerDeduper: TyperDeduper): OnCloseFunction {
        return OnCloseFunction {
            _: Boolean,
            streamSyncSummaries: Map<StreamDescriptor, StreamSyncSummary> ->
            try {
                typerDeduper.typeAndDedupe(streamSyncSummaries)
                typerDeduper.commitFinalTables()
                typerDeduper.cleanup()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }

    private fun toNameNamespacePair(config: WriteConfig): AirbyteStreamNameNamespacePair {
        return AirbyteStreamNameNamespacePair(config.streamName, config.namespace)
    }
}
