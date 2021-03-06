/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.accumulo

import org.apache.accumulo.core.client.Accumulo
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.v2.DataSourceOptions
import org.apache.spark.sql.sources.v2.reader.{DataSourceReader, InputPartition, InputPartitionReader}
import org.apache.spark.sql.types.{DataTypes, StructType}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.apache.log4j.Logger
import java.util.UUID

// TODO: https://github.com/apache/spark/blob/053dd858d38e6107bc71e0aa3a4954291b74f8c8/sql/catalyst/src/main/java/org/apache/spark/sql/connector/read/SupportsReportPartitioning.java
// in head of spark github repo
// import org.apache.spark.sql.connector.read.{SupportsPushDownFilters, SupportsPushDownRequiredColumns}
import org.apache.spark.sql.sources.v2.reader.{SupportsPushDownFilters, SupportsPushDownRequiredColumns}


@SerialVersionUID(1L)
class AccumuloDataSourceReader(schema: StructType, options: DataSourceOptions)
  extends DataSourceReader with Serializable with SupportsPushDownRequiredColumns with SupportsPushDownFilters {
  private val logger = Logger.getLogger(classOf[AccumuloDataSourceReader])

  private val defaultMaxPartitions = 200

  var filters = Array.empty[Filter]

  val rowKeyColumn: String = options.get("rowkey").orElse("rowkey")
  val schemaWithOutRowKey = new StructType(schema.filter { _.name != rowKeyColumn }.toArray)
  
  // initialize output schema with full schema
  private var requiredSchema = {
    // adding rowKey
    val baseSchema = schemaWithOutRowKey.add(rowKeyColumn, DataTypes.StringType, nullable = true)

    // add any output fields we find in a mleap pipeline
    val mleapFields = MLeapUtil.mleapSchemaToCatalyst(options.get("mleap").orElse(""))

    StructType(baseSchema ++ mleapFields)
  }

  private var filterInJuel: Option[String] = None

  override def pruneColumns(requiredSchema: StructType): Unit = {
      this.requiredSchema = requiredSchema
  }

  def readSchema: StructType = requiredSchema

  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    // unfortunately predicates on nested elements are not pushed down by Spark
    // https://issues.apache.org/jira/browse/SPARK-17636
    // https://github.com/apache/spark/pull/22535

    val jsonSchema = AvroUtil.catalystSchemaToJson(schemaWithOutRowKey)
    val result = new FilterToJuel(jsonSchema.attributeToVariableMapping, rowKeyColumn)
      .serializeFilters(filters, options.get("filter").orElse(""))

    this.filters = result.supportedFilters.toArray

    if (result.serializedFilter.length > 0) {
      this.filterInJuel = Some("${" + result.serializedFilter + "}")
      logger.info(s"JUEL filter: ${this.filterInJuel}")
    }

    result.unsupportedFilters.toArray
  }

  override def pushedFilters(): Array[Filter] = filters

  def planInputPartitions: java.util.List[InputPartition[InternalRow]] = {
    val tableName = options.tableName.get
    val maxPartitions = options.getInt("maxPartitions", defaultMaxPartitions)
    val properties = new java.util.Properties()
    // can use .putAll(options.asMap()) due to https://github.com/scala/bug/issues/10418
    options.asMap.asScala.foreach { case (k, v) => properties.setProperty(k, v) }

    // pass GUID to iterator so we can perform fast cache lookup
    // needs to be done on the head node so that all have the same guid
    properties.setProperty("mleapguid", UUID.randomUUID.toString)

    val splits = ArrayBuffer(Array.empty[Byte], Array.empty[Byte])

    val client = Accumulo.newClient().from(properties).build()
    // it's possible to merge on the accumulo side
    // val tableSplits = client.tableOperations().listSplits(tableName, maxPartitions)
    val tableSplits = try {
      client.tableOperations().listSplits(tableName)
    }
    finally {
      client.close()
    }

    // on deployed clusters a table with no split will return a single empty Text instance
    val containsSingleEmptySplit =
      tableSplits.size == 1 &&
        tableSplits.iterator.next.getLength == 0

    if (tableSplits.size > 1 || !containsSingleEmptySplit)
      splits.insertAll(1, tableSplits.asScala.map(_.getBytes))

    // convert splits to ranges
    var ranges = splits.sliding(2).toSeq

    // optionally shuffle
    if (options.getBoolean("shuffle.ranges", true))
      ranges = scala.util.Random.shuffle(ranges)

    // create groups of ranges
    val numReaders = scala.math.min(ranges.length, maxPartitions)
    val batchSize = ranges.length / numReaders
    val batchRanges = ranges.sliding(batchSize, batchSize)

    logger.info(s"Splits '$batchRanges' creating $numReaders readers")

    val foo = batchRanges.map(r => new PartitionReaderFactory(tableName, r,
      schemaWithOutRowKey, requiredSchema, properties, rowKeyColumn, filterInJuel))
      .toSeq.asJava

    new java.util.ArrayList[InputPartition[InternalRow]](foo)
  }
}

class PartitionReaderFactory(tableName: String,
                             ranges: Seq[Seq[Array[Byte]]],
                             inputSchema: StructType,
                             outputSchema: StructType,
                             properties: java.util.Properties,
                             rowKeyColumn: String,
                             filterInJuel: Option[String])
  extends InputPartition[InternalRow] {

  def createPartitionReader: InputPartitionReader[InternalRow] = {

    Logger.getLogger(classOf[AccumuloDataSourceReader]).info(s"Partition reader for $ranges")

    new AccumuloInputPartitionReader(tableName, ranges, inputSchema, outputSchema, properties, rowKeyColumn, filterInJuel)
  }

  //  override def preferredLocations(): Array[String] = Array("ab", "c")
}