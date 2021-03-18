/*
 * Copyright (2020) The Delta Lake Project Authors.
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

package org.apache.spark.sql.delta.commands

import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.{AddFile, FileAction}
import org.apache.spark.sql.delta.files._
import org.apache.spark.sql.delta.schema.ImplicitMetadataOperation
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.util.{AnalysisHelper, SetAccumulator}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

import org.apache.spark.SparkContext
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.{ExpressionEncoder, RowEncoder}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Expression, Literal, PredicateHelper, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.BasePredicate
import org.apache.spark.sql.catalyst.expressions.NamedExpression
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructField, StructType, IntegerType, LongType, StringType}

case class MergeDataSizes(
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  rows: Option[Long] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  files: Option[Long] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  bytes: Option[Long] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  partitions: Option[Long] = None)

/**
 * Represents the state of a single merge clause:
 * - merge clause's (optional) predicate
 * - action type (insert, update, delete)
 * - action's expressions
 */
case class MergeClauseStats(
    condition: Option[String],
    actionType: String,
    actionExpr: Seq[String])

object MergeClauseStats {
  def apply(mergeClause: DeltaMergeIntoClause): MergeClauseStats = {
    MergeClauseStats(
      condition = mergeClause.condition.map(_.sql),
      mergeClause.clauseType.toLowerCase(),
      actionExpr = mergeClause.actions.map(_.sql))
  }
}

/** State for a merge operation */
case class MergeStats(
    // Merge condition expression
    conditionExpr: String,

    // Expressions used in old MERGE stats, now always Null
    updateConditionExpr: String,
    updateExprs: Seq[String],
    insertConditionExpr: String,
    insertExprs: Seq[String],
    deleteConditionExpr: String,

    // Newer expressions used in MERGE with any number of MATCHED/NOT MATCHED
    matchedStats: Seq[MergeClauseStats],
    notMatchedStats: Seq[MergeClauseStats],

    // Data sizes of source and target at different stages of processing
    source: MergeDataSizes,
    targetBeforeSkipping: MergeDataSizes,
    targetAfterSkipping: MergeDataSizes,

    // Data change sizes
    targetFilesRemoved: Long,
    targetFilesAdded: Long,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    changeFilesAdded: Option[Long],
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    targetBytesRemoved: Option[Long],
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    targetBytesAdded: Option[Long],
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    targetPartitionsRemovedFrom: Option[Long],
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    targetPartitionsAddedTo: Option[Long],
    targetRowsCopied: Long,
    targetRowsUpdated: Long,
    targetRowsInserted: Long,
    targetRowsDeleted: Long)

object MergeStats {

  def fromMergeSQLMetrics(
      metrics: Map[String, SQLMetric],
      condition: Expression,
      matchedClauses: Seq[DeltaMergeIntoMatchedClause],
      notMatchedClauses: Seq[DeltaMergeIntoNotMatchedClause],
      isPartitioned: Boolean): MergeStats = {

    def metricValueIfPartitioned(metricName: String): Option[Long] = {
      if (isPartitioned) Some(metrics(metricName).value) else None
    }

    MergeStats(
      // Merge condition expression
      conditionExpr = condition.sql,

      // Newer expressions used in MERGE with any number of MATCHED/NOT MATCHED
      matchedStats = matchedClauses.map(MergeClauseStats(_)),
      notMatchedStats = notMatchedClauses.map(MergeClauseStats(_)),

      // Data sizes of source and target at different stages of processing
      source = MergeDataSizes(rows = Some(metrics("numSourceRows").value)),
      targetBeforeSkipping =
        MergeDataSizes(
          files = Some(metrics("numTargetFilesBeforeSkipping").value),
          bytes = Some(metrics("numTargetBytesBeforeSkipping").value)),
      targetAfterSkipping =
        MergeDataSizes(
          files = Some(metrics("numTargetFilesAfterSkipping").value),
          bytes = Some(metrics("numTargetBytesAfterSkipping").value),
          partitions = metricValueIfPartitioned("numTargetPartitionsAfterSkipping")),

      // Data change sizes
      targetFilesAdded = metrics("numTargetFilesAdded").value,
      changeFilesAdded = metrics.get("numChangeFilesAdded").map(_.value),
      targetFilesRemoved = metrics("numTargetFilesRemoved").value,
      targetBytesAdded = Some(metrics("numTargetBytesAdded").value),
      targetBytesRemoved = Some(metrics("numTargetBytesRemoved").value),
      targetPartitionsRemovedFrom = metricValueIfPartitioned("numTargetPartitionsRemovedFrom"),
      targetPartitionsAddedTo = metricValueIfPartitioned("numTargetPartitionsAddedTo"),
      targetRowsCopied = metrics("numTargetRowsCopied").value,
      targetRowsUpdated = metrics("numTargetRowsUpdated").value,
      targetRowsInserted = metrics("numTargetRowsInserted").value,
      targetRowsDeleted = metrics("numTargetRowsDeleted").value,

      // Deprecated fields
      updateConditionExpr = null,
      updateExprs = null,
      insertConditionExpr = null,
      insertExprs = null,
      deleteConditionExpr = null
    )
  }
}

/**
 * Performs a merge of a source query/table into a Delta table.
 *
 * Issues an error message when the ON search_condition of the MERGE statement can match
 * a single row from the target table with multiple rows of the source table-reference.
 *
 * Algorithm:
 *
 * Phase 1: Find the input files in target that are touched by the rows that satisfy
 *    the condition and verify that no two source rows match with the same target row.
 *    This is implemented as an inner-join using the given condition. See [[findTouchedFiles]]
 *    for more details.
 *
 * Phase 2: Read the touched files again and write new files with updated and/or inserted rows.
 *
 * Phase 3: Use the Delta protocol to atomically remove the touched files and add the new files.
 *
 * @param source            Source data to merge from
 * @param target            Target table to merge into
 * @param targetFileIndex   TahoeFileIndex of the target table
 * @param condition         Condition for a source row to match with a target row
 * @param matchedClauses    All info related to matched clauses.
 * @param notMatchedClauses  All info related to not matched clause.
 * @param migratedSchema    The final schema of the target - may be changed by schema evolution.
 */
case class MergeIntoCommand(
    @transient source: LogicalPlan,
    @transient target: LogicalPlan,
    @transient targetFileIndex: TahoeFileIndex,
    condition: Expression,
    matchedClauses: Seq[DeltaMergeIntoMatchedClause],
    notMatchedClauses: Seq[DeltaMergeIntoNotMatchedClause],
    migratedSchema: Option[StructType]) extends RunnableCommand
  with DeltaCommand with PredicateHelper with AnalysisHelper with ImplicitMetadataOperation {

  import SQLMetrics._
  import MergeIntoCommand._

  override val canMergeSchema: Boolean = conf.getConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE)
  override val canOverwriteSchema: Boolean = false

  @transient private lazy val sc: SparkContext = SparkContext.getOrCreate()
  @transient private lazy val targetDeltaLog: DeltaLog = targetFileIndex.deltaLog

  /** Whether this merge statement has only a single insert (NOT MATCHED) clause. */
  private def isInsertOnly: Boolean = matchedClauses.isEmpty && notMatchedClauses.flatMap { clause => clause match {
    case c: DeltaMergeIntoInsertClause => Some(c)
    case _ => None
  }}.length == 1 && notMatchedClauses.flatMap { clause => clause match {
    case c: DeltaMergeIntoDeleteTargetClause => Some(c)
    case _ => None
  }}.length == 0
  /** Whether this merge statement has only MATCHED clauses. */
  private def isMatchedOnly: Boolean = notMatchedClauses.isEmpty && matchedClauses.nonEmpty

  override lazy val metrics = Map[String, SQLMetric](
    "numSourceRows" -> createMetric(sc, "number of source rows"),
    "numTargetRowsCopied" -> createMetric(sc, "number of target rows rewritten unmodified"),
    "numTargetRowsInserted" -> createMetric(sc, "number of inserted rows"),
    "numTargetRowsUpdated" -> createMetric(sc, "number of updated rows"),
    "numTargetRowsDeleted" -> createMetric(sc, "number of deleted rows"),
    "numTargetFilesBeforeSkipping" -> createMetric(sc, "number of target files before skipping"),
    "numTargetFilesAfterSkipping" -> createMetric(sc, "number of target files after skipping"),
    "numTargetFilesRemoved" -> createMetric(sc, "number of files removed to target"),
    "numTargetFilesAdded" -> createMetric(sc, "number of files added to target"),
    "numTargetBytesBeforeSkipping" -> createMetric(sc, "number of target bytes before skipping"),
    "numTargetBytesAfterSkipping" -> createMetric(sc, "number of target bytes after skipping"),
    "numTargetBytesRemoved" -> createMetric(sc, "number of target bytes removed"),
    "numTargetBytesAdded" -> createMetric(sc, "number of target bytes added"),
    "numTargetPartitionsAfterSkipping" ->
      createMetric(sc, "number of target partitions after skipping"),
    "numTargetPartitionsRemovedFrom" ->
      createMetric(sc, "number of target partitions from which files were removed"),
    "numTargetPartitionsAddedTo" ->
      createMetric(sc, "number of target partitions to which files were added"),
    "executionTimeMs" ->
      createMetric(sc, "time taken to execute the entire operation"),
    "scanTimeMs" ->
      createMetric(sc, "time taken to scan the files for matches"),
    "rewriteTimeMs" ->
      createMetric(sc, "time taken to rewrite the matched files"))

  override def run(spark: SparkSession): Seq[Row] = {
    recordMergeOperation(sqlMetricName = "executionTimeMs") {
      targetDeltaLog.withNewTransaction { deltaTxn =>
        if (target.schema.size != deltaTxn.metadata.schema.size) {
          throw DeltaErrors.schemaChangedSinceAnalysis(
            atAnalysis = target.schema, latestSchema = deltaTxn.metadata.schema)
        }

        if (canMergeSchema) {
          updateMetadata(
            spark, deltaTxn, migratedSchema.getOrElse(target.schema),
            deltaTxn.metadata.partitionColumns, deltaTxn.metadata.configuration,
            isOverwriteMode = false, rearrangeOnly = false)
        }

        val deltaActions = {
          if (isInsertOnly && spark.conf.get(DeltaSQLConf.MERGE_INSERT_ONLY_ENABLED)) {
            writeInsertsOnlyWhenNoMatchedClauses(spark, deltaTxn)
          } else {
            val (filesToRewrite, newWrittenFiles) = {
              writeAllChanges(spark, deltaTxn)
            }
            filesToRewrite.map(_.remove) ++ newWrittenFiles
          }
        }

        deltaTxn.registerSQLMetrics(spark, metrics)
        deltaTxn.commit(
          deltaActions,
          DeltaOperations.Merge(
            Option(condition.sql),
            matchedClauses.map(DeltaOperations.MergePredicate(_)),
            notMatchedClauses.map(DeltaOperations.MergePredicate(_))))

        // Record metrics
        val stats = MergeStats.fromMergeSQLMetrics(
          metrics, condition, matchedClauses, notMatchedClauses,
          deltaTxn.metadata.partitionColumns.nonEmpty)
        recordDeltaEvent(targetFileIndex.deltaLog, "delta.dml.merge.stats", data = stats)

      }
      spark.sharedState.cacheManager.recacheByPlan(spark, target)
    }
    // This is needed to make the SQL metrics visible in the Spark UI. Also this needs
    // to be outside the recordMergeOperation because this method will update some metric.
    val executionId = spark.sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    SQLMetrics.postDriverMetricUpdates(spark.sparkContext, executionId, metrics.values.toSeq)
    Seq.empty
  }

  /**
   * This is an optimization of the case when there is no update clause for the merge.
   * We perform an left anti join on the source data to find the rows to be inserted.
   *
   * This will currently only optimize for the case when there is a _single_ notMatchedClause.
   */
  private def writeInsertsOnlyWhenNoMatchedClauses(
      spark: SparkSession,
      deltaTxn: OptimisticTransaction
    ): Seq[FileAction] = recordMergeOperation(sqlMetricName = "rewriteTimeMs") {

    // UDFs to update metrics
    val incrSourceRowCountExpr = makeMetricUpdateUDF("numSourceRows")
    val incrInsertedCountExpr = makeMetricUpdateUDF("numTargetRowsInserted")

    val outputColNames = getTargetOutputCols(deltaTxn).map(_.name)
    // we use head here since we know there is only a single notMatchedClause
    val outputExprs = notMatchedClauses.head.resolvedActions.map(_.expr) :+ incrInsertedCountExpr
    val outputCols = outputExprs.zip(outputColNames).map { case (expr, name) =>
      new Column(Alias(expr, name)())
    }

    // source DataFrame
    val sourceDF = Dataset.ofRows(spark, source)
      .filter(new Column(incrSourceRowCountExpr))
      .filter(new Column(notMatchedClauses.head.condition.getOrElse(Literal(true))))

    // Skip data based on the merge condition
    val conjunctivePredicates = splitConjunctivePredicates(condition)
    val targetOnlyPredicates =
      conjunctivePredicates.filter(_.references.subsetOf(target.outputSet))
    val dataSkippedFiles = deltaTxn.filterFiles(targetOnlyPredicates)

    // target DataFrame
    val targetDF = Dataset.ofRows(
      spark, buildTargetPlanWithFiles(deltaTxn, dataSkippedFiles))

    val insertDf = sourceDF.join(targetDF, new Column(condition), "leftanti")
      .select(outputCols: _*)

    val newFiles = deltaTxn
      .writeFiles(repartitionIfNeeded(spark, insertDf, deltaTxn.metadata.partitionColumns))

    // Update metrics
    metrics("numTargetFilesBeforeSkipping") += deltaTxn.snapshot.numOfFiles
    metrics("numTargetBytesBeforeSkipping") += deltaTxn.snapshot.sizeInBytes
    val (afterSkippingBytes, afterSkippingPartitions) =
      totalBytesAndDistinctPartitionValues(dataSkippedFiles)
    metrics("numTargetFilesAfterSkipping") += dataSkippedFiles.size
    metrics("numTargetBytesAfterSkipping") += afterSkippingBytes
    metrics("numTargetPartitionsAfterSkipping") += afterSkippingPartitions
    metrics("numTargetFilesRemoved") += 0
    metrics("numTargetBytesRemoved") += 0
    metrics("numTargetPartitionsRemovedFrom") += 0
    val (addedBytes, addedPartitions) = totalBytesAndDistinctPartitionValues(newFiles)
    metrics("numTargetFilesAdded") += newFiles.size
    metrics("numTargetBytesAdded") += addedBytes
    metrics("numTargetPartitionsAddedTo") += addedPartitions
    newFiles
  }

  /**
   * Write new files by reading the touched files and updating/inserting data using the source
   * query/table. This is implemented using a full|right-outer-join using the merge condition.
   */
  private def writeAllChanges(
    spark: SparkSession,
    deltaTxn: OptimisticTransaction,
  ): (Seq[AddFile], Seq[FileAction]) = recordMergeOperation(sqlMetricName = "rewriteTimeMs") {

    import spark.implicits._

    val targetOutputCols = getTargetOutputCols(deltaTxn)

    val targetOnlyPredicates = splitConjunctivePredicates(condition).filter(_.references.subsetOf(target.outputSet))
    val dataSkippedFiles = deltaTxn.filterFiles(targetOnlyPredicates)

    // Generate a new logical plan that has same output attributes exprIds as the target plan.
    // This allows us to apply the existing resolved update/insert expressions.
    val joinType = if (isMatchedOnly &&
      spark.conf.get(DeltaSQLConf.MERGE_MATCHED_ONLY_ENABLED)) {
      "rightOuter"
    } else {
      "full"
    }

    logDebug(s"""writeAllChanges using $joinType join:
                |  source.output: ${source.outputSet}
                |  target.output: ${target.outputSet}
                |  condition: $condition
       """.stripMargin)

    // UDFs to update metrics
    val incrSourceRowCountExpr = makeMetricUpdateUDF("numSourceRows")
    val incrUpdatedCountExpr = makeMetricUpdateUDF("numTargetRowsUpdated")
    val incrInsertedCountExpr = makeMetricUpdateUDF("numTargetRowsInserted")
    val incrNoopCountExpr = makeMetricUpdateUDF("numTargetRowsCopied")
    val incrDeletedCountExpr = makeMetricUpdateUDF("numTargetRowsDeleted")

    // Accumulator to collect all the distinct touched files
    val touchedFilesAccum = new SetAccumulator[String]()
    spark.sparkContext.register(touchedFilesAccum, TOUCHED_FILES_ACCUM_NAME)

    // Apply an outer join to find both, matches and non-matches. We are adding two boolean fields
    // with value `true`, one to each side of the join. Whether this field is null or not after
    // the outer join, will allow us to identify whether the resultant joined row was a
    // matched inner result or an unmatched result with null on one side.
    val joinedDF = {
      val sourceDF = Dataset.ofRows(spark, source)
        .withColumn(SOURCE_ROW_PRESENT_COL, new Column(incrSourceRowCountExpr))
      val targetDF = Dataset.ofRows(spark, buildTargetPlanWithFiles(deltaTxn, dataSkippedFiles))
        .withColumn(TARGET_ROW_PRESENT_COL, lit(true))
        .withColumn(FILE_NAME_COL, input_file_name())
        .withColumn(ROW_ID_COL, monotonically_increasing_id())
      sourceDF.join(targetDF, new Column(condition), joinType)
    }
    val joinedPlan = joinedDF.queryExecution.analyzed

    def resolveOnJoinedPlan(exprs: Seq[Expression]): Seq[Expression] = {
      exprs.map { expr => tryResolveReferences(spark)(expr, joinedPlan) }
    }

    // expressions to include the first output for filtering
    val filenameCol = joinedPlan.output.filter { output => output.name == FILE_NAME_COL }.head
    val rowIdCol = joinedPlan.output.filter { output => output.name == ROW_ID_COL }.head

    def matchedClauseOutput(clause: DeltaMergeIntoMatchedClause): Seq[Expression] = {
      val exprs = clause match {
        case u: DeltaMergeIntoUpdateClause =>
          // Generate update expressions and set ROW_DELETED_COL = false
          u.resolvedActions.map(_.expr) :+
            Literal(TARGET_ROW_UPDATE) :+
            filenameCol :+
            rowIdCol :+
            incrUpdatedCountExpr
        case _: DeltaMergeIntoDeleteClause =>
          // Generate expressions to set the ROW_DELETED_COL = true
          targetOutputCols :+
            Literal(TARGET_ROW_DELETE) :+
            filenameCol :+
            rowIdCol :+
            incrDeletedCountExpr
      }
      resolveOnJoinedPlan(exprs)
    }

    def notMatchedClauseOutput(clause: DeltaMergeIntoNotMatchedClause): Seq[Expression] = {
      val exprs = clause match {
        case u: DeltaMergeIntoInsertClause =>
          // Generate update expressions and set ROW_DELETED_COL = false
          u.resolvedActions.map(_.expr) :+
            Literal(TARGET_ROW_INSERT) :+
            filenameCol :+
            rowIdCol :+
            incrInsertedCountExpr
        case _: DeltaMergeIntoDeleteTargetClause =>
          // Generate expressions to set the ROW_DELETED_COL = true
          targetOutputCols :+
          Literal(TARGET_ROW_DELETE) :+
          filenameCol :+
          rowIdCol :+
          incrDeletedCountExpr
      }
      resolveOnJoinedPlan(exprs)
    }

    def clauseCondition(clause: DeltaMergeIntoClause): Expression = {
      // if condition is None, then expression always evaluates to true
      val condExpr = clause.condition.getOrElse(Literal(true))
      resolveOnJoinedPlan(Seq(condExpr)).head
    }

    val joinedRowEncoder = RowEncoder(joinedPlan.schema)
    // this schema includes the data required to identify which files to rewrite
    val outputRowEncoder = RowEncoder(
      StructType(deltaTxn.metadata.schema.fields.toList :::
      StructField(TARGET_ROW_OUTCOME, IntegerType, true) ::
      StructField(FILE_NAME_COL, StringType, true) ::
      StructField(ROW_ID_COL, LongType, true) :: Nil)
    ).resolveAndBind()

    val processor = new JoinedRowProcessor(
      targetRowHasNoMatch = resolveOnJoinedPlan(Seq(col(SOURCE_ROW_PRESENT_COL).isNull.expr)).head,
      sourceRowHasNoMatch = resolveOnJoinedPlan(Seq(col(TARGET_ROW_PRESENT_COL).isNull.expr)).head,
      matchedConditions = matchedClauses.map(clauseCondition),
      matchedOutputs = matchedClauses.map(matchedClauseOutput),
      notMatchedInTargetConditions = notMatchedClauses.flatMap { clause => clause match {
        case c: DeltaMergeIntoInsertClause => Some(c)
        case _ => None
      }}.map(clauseCondition),
      notMatchedInTargetOutputs = notMatchedClauses.map(notMatchedClauseOutput),
      notMatchedInSourceConditions = notMatchedClauses.flatMap { clause => clause match {
        case c: DeltaMergeIntoDeleteTargetClause => Some(c)
        case _ => None
      }}.map(clauseCondition),
      notMatchedInSourceOutputs = Seq(resolveOnJoinedPlan(
        targetOutputCols :+
        Literal(TARGET_ROW_DELETE) :+
        filenameCol :+
        rowIdCol :+
        incrNoopCountExpr)
      ),
      noopCopyOutput = resolveOnJoinedPlan(
        targetOutputCols :+
        Literal(TARGET_ROW_COPY) :+
        filenameCol :+
        rowIdCol :+
        incrNoopCountExpr
      ),
      skippedRowOutput = resolveOnJoinedPlan(
        targetOutputCols :+
        Literal(TARGET_ROW_SKIP) :+
        filenameCol :+
        rowIdCol :+
        Literal(true)
      ),
      joinedAttributes = joinedPlan.output,
      joinedRowEncoder = joinedRowEncoder,
      outputRowEncoder = outputRowEncoder,
      touchedFilesAccum = touchedFilesAccum)

    val calculatedDF =
      Dataset.ofRows(spark, joinedPlan).mapPartitions(processor.processPartition)(outputRowEncoder)
    logDebug("writeAllChanges: join output plan:\n" + calculatedDF.queryExecution)

    // register the processed partion to be cached as the output is used twice:
    // - to test for cartesian product
    // - to calculate the list of records that need to be written
    calculatedDF.persist

    // calculate frequency of matches per source row to determine if join has resulted in duplicate
    // matches this will also execute the processPartition function which will populate the
    // touchedFilesAccum accumulator
    val matchedRowCounts = calculatedDF
      .filter(col(ROW_ID_COL).isNotNull)
      .groupBy(ROW_ID_COL)
      .agg(count("*").alias("count"))

    // Throw error if multiple matches are ambiguous or cannot be computed correctly.
    val canBeComputedUnambiguously = {
      // Multiple matches are not ambiguous when there is only one unconditional delete as
      // all the matched row pairs in the 2nd join in `writeAllChanges` will get deleted.
      val isUnconditionalDelete = matchedClauses.headOption match {
        case Some(DeltaMergeIntoDeleteClause(None)) => true
        case _ => false
      }
      matchedClauses.size == 1 && isUnconditionalDelete
    }
    if (matchedRowCounts.filter("count > 1").count() != 0 && !canBeComputedUnambiguously) {
      throw DeltaErrors.multipleSourceRowMatchingTargetRowInMergeException(spark)
    }

    // use the accumulated values to detail which files to remove
    val touchedFileNames = touchedFilesAccum.value.iterator().asScala.toSet.toSeq
    val nameToAddFileMap = generateCandidateFileMap(targetDeltaLog.dataPath, dataSkippedFiles)
    val removeFiles = touchedFileNames.map(f => getTouchedFile(targetDeltaLog.dataPath, f, nameToAddFileMap))

    // use the accumulated file names to make a new dataframe for joining
    val touchedFileNamesDF = touchedFileNames.toDF(TOUCHED_FILE_NAME_COL)

    // join to the touched files dataframe to determine which records need to be written
    val outputDF = calculatedDF
      .join(touchedFileNamesDF, col(FILE_NAME_COL) === col(TOUCHED_FILE_NAME_COL), "left")
      .filter { row =>
        val outcome = row.getInt(row.fieldIndex(TARGET_ROW_OUTCOME))
        val touchedFileNameNotNull = !row.isNullAt(row.fieldIndex(TOUCHED_FILE_NAME_COL))

        // TARGET_ROW_COPY copy rows if they are in a file impacted by an update or delete
        (outcome == TARGET_ROW_COPY && touchedFileNameNotNull) ||
        // TARGET_ROW_INSERT rows if they are new
        (outcome == TARGET_ROW_INSERT) ||
        // TARGET_ROW_UPDATE if they changed at all
        (outcome == TARGET_ROW_UPDATE)
        // TARGET_ROW_DELETE is filtered out here
        // TARGET_ROW_SKIP is filtered out here
      }
      .drop(TARGET_ROW_OUTCOME)
      .drop(TOUCHED_FILE_NAME_COL)
      .drop(FILE_NAME_COL)
      .drop(ROW_ID_COL)


    // Write to Delta
    val newFiles = deltaTxn
      .writeFiles(repartitionIfNeeded(spark, outputDF, deltaTxn.metadata.partitionColumns))


    val touchedAddFiles = touchedFileNames.map(f =>
      getTouchedFile(targetDeltaLog.dataPath, f, nameToAddFileMap))

    // Update metrics
    metrics("numTargetFilesBeforeSkipping") += deltaTxn.snapshot.numOfFiles
    metrics("numTargetBytesBeforeSkipping") += deltaTxn.snapshot.sizeInBytes
    val (afterSkippingBytes, afterSkippingPartitions) =
      totalBytesAndDistinctPartitionValues(dataSkippedFiles)
    metrics("numTargetFilesAfterSkipping") += dataSkippedFiles.size
    metrics("numTargetBytesAfterSkipping") += afterSkippingBytes
    metrics("numTargetPartitionsAfterSkipping") += afterSkippingPartitions
    val (removedBytes, removedPartitions) = totalBytesAndDistinctPartitionValues(removeFiles)
    metrics("numTargetFilesRemoved") += removeFiles.size
    metrics("numTargetBytesRemoved") += removedBytes
    metrics("numTargetPartitionsRemovedFrom") += removedPartitions
    val (addedBytes, addedPartitions) = totalBytesAndDistinctPartitionValues(newFiles)
    metrics("numTargetFilesAdded") += newFiles.size
    metrics("numTargetBytesAdded") += addedBytes
    metrics("numTargetPartitionsAddedTo") += addedPartitions

    // remove dataset from cache
    calculatedDF.unpersist

    (removeFiles, newFiles)
  }

  /**
   * Build a new logical plan using the given `files` that has the same output columns (exprIds)
   * as the `target` logical plan, so that existing update/insert expressions can be applied
   * on this new plan.
   */
  private def buildTargetPlanWithFiles(
    deltaTxn: OptimisticTransaction,
    files: Seq[AddFile]): LogicalPlan = {
    val plan = deltaTxn.deltaLog.createDataFrame(deltaTxn.snapshot, files).queryExecution.analyzed

    // For each plan output column, find the corresponding target output column (by name) and
    // create an alias
    val aliases = plan.output.map {
      case newAttrib: AttributeReference =>
        val existingTargetAttrib = getTargetOutputCols(deltaTxn).find(_.name == newAttrib.name)
          .getOrElse {
            throw new AnalysisException(
              s"Could not find ${newAttrib.name} among the existing target output " +
                s"${getTargetOutputCols(deltaTxn)}")
          }.asInstanceOf[AttributeReference]
        Alias(newAttrib, existingTargetAttrib.name)(exprId = existingTargetAttrib.exprId)
    }
    Project(aliases, plan)
  }

  /** Expressions to increment SQL metrics */
  private def makeMetricUpdateUDF(name: String): Expression = {
    // only capture the needed metric in a local variable
    val metric = metrics(name)
    udf { () => { metric += 1; true }}.asNondeterministic().apply().expr
  }

  private def seqToString(exprs: Seq[Expression]): String = exprs.map(_.sql).mkString("\n\t")

  private def getTargetOutputCols(txn: OptimisticTransaction): Seq[NamedExpression] = {
    txn.metadata.schema.map { col =>
      target.output.find(attr => conf.resolver(attr.name, col.name)).getOrElse {
        Alias(Literal(null, col.dataType), col.name)()
      }
    }
  }

  /**
   * Repartitions the output DataFrame by the partition columns if table is partitioned
   * and `merge.repartitionBeforeWrite.enabled` is set to true.
   */
  protected def repartitionIfNeeded(
      spark: SparkSession,
      df: DataFrame,
      partitionColumns: Seq[String]): DataFrame = {
    val defaultShufflePartitions = spark.conf.get("spark.sql.shuffle.partitions").toInt
    val arcDeltaPartitions = Try(spark.conf.get("arc.delta.partitions").toInt).getOrElse(defaultShufflePartitions)
    if (partitionColumns.nonEmpty && spark.conf.get(DeltaSQLConf.MERGE_REPARTITION_BEFORE_WRITE)) {
      df.repartition(arcDeltaPartitions, partitionColumns.map(col): _*)
    } else if (df.rdd.partitions.length > arcDeltaPartitions) {
      df.repartition(arcDeltaPartitions)
    } else {
      df
    }
  }

  /**
   * Execute the given `thunk` and return its result while recording the time taken to do it.
   *
   * @param sqlMetricName name of SQL metric to update with the time taken by the thunk
   * @param thunk the code to execute
   */
  private def recordMergeOperation[A](sqlMetricName: String = null)(thunk: => A): A = {
    val startTimeNs = System.nanoTime()
    val r = thunk
    val timeTakenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNs)
    if (sqlMetricName != null && timeTakenMs > 0) {
      metrics(sqlMetricName) += timeTakenMs
    }
    r
  }
}

object MergeIntoCommand {
  /**
   * Spark UI will track all normal accumulators along with Spark tasks to show them on Web UI.
   * However, the accumulator used by `MergeIntoCommand` can store a very large value since it
   * tracks all files that need to be rewritten. We should ask Spark UI to not remember it,
   * otherwise, the UI data may consume lots of memory. Hence, we use the prefix `internal.metrics.`
   * to make this accumulator become an internal accumulator, so that it will not be tracked by
   * Spark UI.
   */
  val TOUCHED_FILES_ACCUM_NAME = "internal.metrics.MergeIntoDelta.touchedFiles"

  val ROW_ID_COL = "_row_id_"
  val FILE_NAME_COL = "_file_name_"
  val TOUCHED_FILE_NAME_COL = "_touched_file_name_"
  val SOURCE_ROW_PRESENT_COL = "_source_row_present_"
  val TARGET_ROW_PRESENT_COL = "_target_row_present_"

  val TARGET_ROW_OUTCOME = "_target_row_outcome_"
  val TARGET_ROW_COPY = 0
  val TARGET_ROW_DELETE = 1
  val TARGET_ROW_INSERT = 2
  val TARGET_ROW_SKIP = 3
  val TARGET_ROW_UPDATE = 4

  class JoinedRowProcessor(
      targetRowHasNoMatch: Expression,
      sourceRowHasNoMatch: Expression,
      matchedConditions: Seq[Expression],
      matchedOutputs: Seq[Seq[Expression]],
      notMatchedInTargetConditions: Seq[Expression],
      notMatchedInTargetOutputs: Seq[Seq[Expression]],
      notMatchedInSourceConditions: Seq[Expression],
      notMatchedInSourceOutputs: Seq[Seq[Expression]],
      noopCopyOutput: Seq[Expression],
      skippedRowOutput: Seq[Expression],
      joinedAttributes: Seq[Attribute],
      joinedRowEncoder: ExpressionEncoder[Row],
      outputRowEncoder: ExpressionEncoder[Row],
      touchedFilesAccum: SetAccumulator[String]) extends Serializable {

    private def generateProjection(exprs: Seq[Expression]): UnsafeProjection = {
      UnsafeProjection.create(exprs, joinedAttributes)
    }

    private def generatePredicate(expr: Expression): BasePredicate = {
      GeneratePredicate.generate(expr, joinedAttributes)
    }

    def processPartition(rowIterator: Iterator[Row]): Iterator[Row] = {

      val targetRowHasNoMatchPred = generatePredicate(targetRowHasNoMatch)
      val sourceRowHasNoMatchPred = generatePredicate(sourceRowHasNoMatch)
      val matchedPreds = matchedConditions.map(generatePredicate)
      val matchedProjs = matchedOutputs.map(generateProjection)
      val notMatchedInTargetPreds = notMatchedInTargetConditions.map(generatePredicate)
      val notMatchedInTargetProjs = notMatchedInTargetOutputs.map(generateProjection)
      val notMatchedInSourcePreds = notMatchedInSourceConditions.map(generatePredicate)
      val notMatchedInSourceProjs = notMatchedInSourceOutputs.map(generateProjection)
      val noopCopyProj = generateProjection(noopCopyOutput)
      val skippedRowProj = generateProjection(skippedRowOutput)
      val outputProj = UnsafeProjection.create(outputRowEncoder.schema)

      def processRow(inputRow: InternalRow, filenameFieldIndex: Int): InternalRow = {

        // Target row did not match with any source row, so maybe delete or just copy
        if (targetRowHasNoMatchPred.eval(inputRow)) {

          // find (predicate, projection) pair whose predicate satisfies inputRow
          val pair = (notMatchedInSourcePreds zip notMatchedInSourceProjs).find {
            case (predicate, _) => predicate.eval(inputRow)
          }

          pair match {
            case Some((_, projections)) => {
              touchedFilesAccum.add(inputRow.getString(filenameFieldIndex))
              projections.apply(inputRow)
            }
            case None => noopCopyProj.apply(inputRow)
          }

        // if record exists in source but not in target then maybe insert
        } else if (sourceRowHasNoMatchPred.eval(inputRow)) {
          // find (predicate, projection) pair whose predicate satisfies inputRow
          val pair = (notMatchedInTargetPreds zip notMatchedInTargetProjs).find {
            case (predicate, _) => predicate.eval(inputRow)
          }
          pair match {
            case Some((_, projections)) => {
              projections.apply(inputRow)
            }
            case None => skippedRowProj.apply(inputRow)
          }

          // Source row matched with target row, so update the target row
        } else {
          // find (predicate, projection) pair whose predicate satisfies inputRow
          val pair = (matchedPreds zip matchedProjs).find {
            case (predicate, _) => predicate.eval(inputRow)
          }

          pair match {
            case Some((_, projections)) => {
              touchedFilesAccum.add(inputRow.getString(filenameFieldIndex))
              projections.apply(inputRow)
            }
            case None => noopCopyProj.apply(inputRow)
          }
        }
      }

      val toRow = joinedRowEncoder.createSerializer()
      val fromRow = outputRowEncoder.createDeserializer()
      val filenameFieldIndex = joinedRowEncoder.schema.fieldIndex(FILE_NAME_COL)
      rowIterator
        .map{ row => toRow(row) }
        .map{ row => processRow(row, filenameFieldIndex) }
        .map{ row => fromRow(outputProj(row)) }
    }
  }

  /** Count the number of distinct partition values among the AddFiles in the given set. */
  def totalBytesAndDistinctPartitionValues(files: Seq[FileAction]): (Long, Int) = {
    val distinctValues = new mutable.HashSet[Map[String, String]]()
    var bytes = 0L
    val iter = files.collect { case a: AddFile => a }.iterator
    while (iter.hasNext) {
      val file = iter.next()
      distinctValues += file.partitionValues
      bytes += file.size
    }
    // If the only distinct value map is an empty map, then it must be an unpartitioned table.
    // Return 0 in that case.
    val numDistinctValues =
      if (distinctValues.size == 1 && distinctValues.head.isEmpty) 0 else distinctValues.size
    (bytes, numDistinctValues)
  }
}
