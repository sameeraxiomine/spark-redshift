/*
 * Copyright 2015 TouchType Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.redshift

import java.io.File
import java.net.URI

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration.Rule
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.InputFormat
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.fs.s3native.S3NInMemoryFileSystem
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, Matchers}

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import com.databricks.spark.redshift.Parameters.MergedParameters

private class TestContext extends SparkContext("local", "RedshiftSourceSuite") {

  /**
   * A text file containing fake unloaded Redshift data of all supported types
   */
  val testData = new File("src/test/resources/redshift_unload_data.txt").toURI.toString

  override def newAPIHadoopFile[K, V, F <: InputFormat[K, V]](
      path: String,
      fClass: Class[F],
      kClass: Class[K],
      vClass: Class[V],
      conf: Configuration = hadoopConfiguration): RDD[(K, V)] = {
    super.newAPIHadoopFile[K, V, F](testData, fClass, kClass, vClass, conf)
  }
}

/**
 * Tests main DataFrame loading and writing functionality
 */
class RedshiftSourceSuite
  extends QueryTest
  with Matchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  /**
   * Spark Context with Hadoop file overridden to point at our local test data file for this suite,
   * no matter what temp directory was generated and requested.
   */
  private var sc: SparkContext = _

  private var testSqlContext: SQLContext = _

  private var expectedDataDF: DataFrame = _

  private var mockS3Client: AmazonS3Client = _

  private var s3FileSystem: FileSystem = _

  private val s3TempDir: String = "s3n://test-bucket/temp-dir/"

  // Parameters common to most tests. Some parameters are overridden in specific tests.
  private def defaultParams: Map[String, String] = Map(
    "url" -> "jdbc:redshift://foo/bar",
    "tempdir" -> s3TempDir,
    "dbtable" -> "test_table")

  override def beforeAll(): Unit = {
    super.beforeAll()
    sc = new TestContext
    sc.hadoopConfiguration.set("fs.s3n.impl", classOf[S3NInMemoryFileSystem].getName)
    // We need to use a DirectOutputCommitter to work around an issue which occurs with renames
    // while using the mocked S3 filesystem.
    sc.hadoopConfiguration.set("spark.sql.sources.outputCommitterClass",
      classOf[DirectOutputCommitter].getName)
    sc.hadoopConfiguration.set("fs.s3.awsAccessKeyId", "test1")
    sc.hadoopConfiguration.set("fs.s3.awsSecretAccessKey", "test2")
    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "test1")
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "test2")
    // Configure a mock S3 client so that we don't hit errors when trying to access AWS in tests.
    mockS3Client = Mockito.mock(classOf[AmazonS3Client], Mockito.RETURNS_SMART_NULLS)
    when(mockS3Client.getBucketLifecycleConfiguration(any())).thenReturn(
      new BucketLifecycleConfiguration().withRules(
        new Rule().withPrefix("").withStatus(BucketLifecycleConfiguration.ENABLED)
      ))
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    s3FileSystem = FileSystem.get(new URI(s3TempDir), sc.hadoopConfiguration)
    testSqlContext = new SQLContext(sc)
    expectedDataDF =
      testSqlContext.createDataFrame(sc.parallelize(TestUtils.expectedData), TestUtils.testSchema)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    testSqlContext = null
    expectedDataDF = null
    mockS3Client = null
    FileSystem.closeAll()
  }

  override def afterAll(): Unit = {
    sc.stop()
    super.afterAll()
  }

  test("DefaultSource can load Redshift UNLOAD output to a DataFrame") {
    val expectedQuery = (
      "UNLOAD \\('SELECT \"testbyte\", \"testbool\", \"testdate\", \"testdouble\"," +
      " \"testfloat\", \"testint\", \"testlong\", \"testshort\", \"teststring\", " +
      "\"testtimestamp\" " +
      "FROM test_table '\\) " +
      "TO '.*' " +
      "WITH CREDENTIALS 'aws_access_key_id=test1;aws_secret_access_key=test2' " +
      "ESCAPE").r
    val mockRedshift =
      new MockRedshift(defaultParams("url"), Map("test_table" -> TestUtils.testSchema))

    // Assert that we've loaded and converted all data in the test file
    val source = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    val relation = source.createRelation(testSqlContext, defaultParams)
    val df = testSqlContext.baseRelationToDataFrame(relation)
    checkAnswer(df, TestUtils.expectedData)
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(Seq(expectedQuery))
  }

  test("Can load output of Redshift queries") {
    // scalastyle:off
    val expectedJDBCQuery =
      """
        |UNLOAD \('SELECT "testbyte", "testbool" FROM
        |  \(select testbyte, testbool
        |    from test_table
        |    where teststring = \\'Unicode\\'\\'s樂趣\\'\) '\)
      """.stripMargin.lines.map(_.trim).mkString(" ").trim.r
    val query =
      """select testbyte, testbool from test_table where teststring = 'Unicode''s樂趣'"""
    // scalastyle:on
    val querySchema =
      StructType(Seq(StructField("testbyte", ByteType), StructField("testbool", BooleanType)))

    // Test with dbtable parameter that wraps the query in parens:
    {
      val params = defaultParams + ("dbtable" -> s"($query)")
      val mockRedshift =
        new MockRedshift(defaultParams("url"), Map(params("dbtable") -> querySchema))
      val relation = new DefaultSource(
        mockRedshift.jdbcWrapper, _ => mockS3Client).createRelation(testSqlContext, params)
      testSqlContext.baseRelationToDataFrame(relation).collect()
      mockRedshift.verifyThatConnectionsWereClosed()
      mockRedshift.verifyThatExpectedQueriesWereIssued(Seq(expectedJDBCQuery))
    }

    // Test with query parameter
    {
      val params = defaultParams - "dbtable" + ("query" -> query)
      val mockRedshift = new MockRedshift(defaultParams("url"), Map(s"($query)" -> querySchema))
      val relation = new DefaultSource(
        mockRedshift.jdbcWrapper, _ => mockS3Client).createRelation(testSqlContext, params)
      testSqlContext.baseRelationToDataFrame(relation).collect()
      mockRedshift.verifyThatConnectionsWereClosed()
      mockRedshift.verifyThatExpectedQueriesWereIssued(Seq(expectedJDBCQuery))
    }
  }

  test("DefaultSource supports simple column filtering") {
    val expectedQuery = (
      "UNLOAD \\('SELECT \"testbyte\", \"testbool\" FROM test_table '\\) " +
      "TO '.*' " +
      "WITH CREDENTIALS 'aws_access_key_id=test1;aws_secret_access_key=test2' " +
      "ESCAPE").r
    val mockRedshift =
      new MockRedshift(defaultParams("url"), Map("test_table" -> TestUtils.testSchema))
    // Construct the source with a custom schema
    val source = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    val relation = source.createRelation(testSqlContext, defaultParams, TestUtils.testSchema)

    val rdd = relation.asInstanceOf[PrunedFilteredScan]
      .buildScan(Array("testbyte", "testbool"), Array.empty[Filter])
    val prunedExpectedValues = Array(
      Row(1.toByte, true),
      Row(1.toByte, false),
      Row(0.toByte, null),
      Row(0.toByte, false),
      Row(null, null))
    assert(rdd.collect() === prunedExpectedValues)
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(Seq(expectedQuery))
  }

  test("DefaultSource supports user schema, pruned and filtered scans") {
    // scalastyle:off
    val expectedQuery = (
      "UNLOAD \\('SELECT \"testbyte\", \"testbool\" " +
        "FROM test_table " +
        "WHERE \"testbool\" = true " +
        "AND \"teststring\" = \\\\'Unicode\\\\'\\\\'s樂趣\\\\' " +
        "AND \"testdouble\" > 1000.0 " +
        "AND \"testdouble\" < 1.7976931348623157E308 " +
        "AND \"testfloat\" >= 1.0 " +
        "AND \"testint\" <= 43'\\) " +
      "TO '.*' " +
      "WITH CREDENTIALS 'aws_access_key_id=test1;aws_secret_access_key=test2' " +
      "ESCAPE").r
    // scalastyle:on
    val mockRedshift =
      new MockRedshift(defaultParams("url"), Map("test_table" -> TestUtils.testSchema))

    // Construct the source with a custom schema
    val source = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    val relation = source.createRelation(testSqlContext, defaultParams, TestUtils.testSchema)

    // Define a simple filter to only include a subset of rows
    val filters: Array[Filter] = Array(
      EqualTo("testbool", true),
      // scalastyle:off
      EqualTo("teststring", "Unicode's樂趣"),
      // scalastyle:on
      GreaterThan("testdouble", 1000.0),
      LessThan("testdouble", Double.MaxValue),
      GreaterThanOrEqual("testfloat", 1.0f),
      LessThanOrEqual("testint", 43))
    val rdd = relation.asInstanceOf[PrunedFilteredScan]
      .buildScan(Array("testbyte", "testbool"), filters)

    // Technically this assertion should check that the RDD only returns a single row, but
    // since we've mocked out Redshift our WHERE clause won't have had any effect.
    assert(rdd.collect().contains(Row(1, true)))
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(Seq(expectedQuery))
  }

  test("DefaultSource serializes data as Avro, then sends Redshift COPY command") {
    val params = defaultParams ++ Map(
      "postactions" -> "GRANT SELECT ON %s TO jeremy",
      "diststyle" -> "KEY",
      "distkey" -> "testint")

    val expectedCommands = Seq(
      "DROP TABLE IF EXISTS test_table_staging_.*".r,
      "CREATE TABLE IF NOT EXISTS test_table_staging.* DISTSTYLE KEY DISTKEY \\(testint\\).*".r,
      "COPY test_table_staging_.*".r,
      "GRANT SELECT ON test_table_staging.+ TO jeremy".r,
      """
        | BEGIN;
        | ALTER TABLE test_table RENAME TO test_table_backup_.*;
        | ALTER TABLE test_table_staging_.* RENAME TO test_table;
        | DROP TABLE test_table_backup_.*;
        | END;
      """.stripMargin.trim.r,
      "DROP TABLE IF EXISTS test_table_staging_.*".r)

    val mockRedshift =
      new MockRedshift(defaultParams("url"), Map("test_table" -> TestUtils.testSchema))

    val relation = RedshiftRelation(
      mockRedshift.jdbcWrapper,
      _ => mockS3Client,
      Parameters.mergeParameters(params),
      userSchema = None)(testSqlContext)
    relation.asInstanceOf[InsertableRelation].insert(expectedDataDF, overwrite = true)

    // Make sure we wrote the data out ready for Redshift load, in the expected formats.
    // The data should have been written to a random subdirectory of `tempdir`. Since we clear
    // `tempdir` between every unit test, there should only be one directory here.
    assert(s3FileSystem.listStatus(new Path(s3TempDir)).length === 1)
    val dirWithAvroFiles = s3FileSystem.listStatus(new Path(s3TempDir)).head.getPath.toUri.toString
    val written = testSqlContext.read.format("com.databricks.spark.avro").load(dirWithAvroFiles)
    checkAnswer(written, TestUtils.expectedDataWithConvertedTimesAndDates)
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(expectedCommands)
  }

  test("Cannot write table with column names that become ambiguous under case insensitivity") {
    val mockRedshift =
      new MockRedshift(defaultParams("url"), Map("test_table" -> TestUtils.testSchema))

    val schema = StructType(Seq(StructField("a", IntegerType), StructField("A", IntegerType)))
    val df = testSqlContext.createDataFrame(sc.emptyRDD[Row], schema)
    val writer = new RedshiftWriter(mockRedshift.jdbcWrapper, _ => mockS3Client)

    intercept[IllegalArgumentException] {
      writer.saveToRedshift(
        testSqlContext, df, SaveMode.Append, Parameters.mergeParameters(defaultParams))
    }
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(Seq.empty)
  }

  test("Failed copies are handled gracefully when using a staging table") {
    val params = defaultParams ++ Map("usestagingtable" -> "true")

    val mockRedshift = new MockRedshift(
      defaultParams("url"),
      Map("test_table" -> TestUtils.testSchema),
      jdbcQueriesThatShouldFail = Seq("COPY test_table_staging_.*".r))

    val expectedCommands = Seq(
      "DROP TABLE IF EXISTS test_table_staging_.*".r,
      "CREATE TABLE IF NOT EXISTS test_table_staging.*".r,
      "COPY test_table_staging_.*".r,
      ".*FROM stl_load_errors.*".r,
      "DROP TABLE IF EXISTS test_table_staging.*".r
    )

    val source = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    intercept[Exception] {
      source.createRelation(testSqlContext, SaveMode.Overwrite, params, expectedDataDF)
      mockRedshift.verifyThatConnectionsWereClosed()
      mockRedshift.verifyThatExpectedQueriesWereIssued(expectedCommands)
    }
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(expectedCommands)
  }

  test("Append SaveMode doesn't destroy existing data") {
    val expectedCommands =
      Seq("CREATE TABLE IF NOT EXISTS test_table .*".r,
          "COPY test_table .*".r)

    val mockRedshift = new MockRedshift(defaultParams("url"), Map(defaultParams("dbtable") -> null))

    val source = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    val savedDf =
      source.createRelation(testSqlContext, SaveMode.Append, defaultParams, expectedDataDF)

    // This test is "appending" to an empty table, so we expect all our test data to be
    // the only content in the returned data frame.
    // The data should have been written to a random subdirectory of `tempdir`. Since we clear
    // `tempdir` between every unit test, there should only be one directory here.
    assert(s3FileSystem.listStatus(new Path(s3TempDir)).length === 1)
    val dirWithAvroFiles = s3FileSystem.listStatus(new Path(s3TempDir)).head.getPath.toUri.toString
    val written = testSqlContext.read.format("com.databricks.spark.avro").load(dirWithAvroFiles)
    checkAnswer(written, TestUtils.expectedDataWithConvertedTimesAndDates)
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(expectedCommands)
  }

  test("configuring maxlength on string columns") {
    val longStrMetadata = new MetadataBuilder().putLong("maxlength", 512).build()
    val shortStrMetadata = new MetadataBuilder().putLong("maxlength", 10).build()
    val schema = StructType(
      StructField("long_str", StringType, metadata = longStrMetadata) ::
      StructField("short_str", StringType, metadata = shortStrMetadata) ::
      StructField("default_str", StringType) ::
      Nil)
    val df = testSqlContext.createDataFrame(sc.emptyRDD[Row], schema)
    val createTableCommand =
      DefaultRedshiftWriter.createTableSql(df, MergedParameters.apply(defaultParams)).trim
    val expectedCreateTableCommand =
      """CREATE TABLE IF NOT EXISTS test_table ("long_str" VARCHAR(512),""" +
        """ "short_str" VARCHAR(10), "default_str" TEXT)"""
    assert(createTableCommand === expectedCreateTableCommand)
  }

  test("Respect SaveMode.ErrorIfExists when table exists") {
    val mockRedshift = new MockRedshift(defaultParams("url"), Map(defaultParams("dbtable") -> null))
    val errIfExistsSource = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    intercept[Exception] {
      errIfExistsSource.createRelation(
        testSqlContext, SaveMode.ErrorIfExists, defaultParams, expectedDataDF)
    }
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(Seq.empty)
  }

  test("Do nothing when table exists if SaveMode = Ignore") {
    val mockRedshift = new MockRedshift(defaultParams("url"), Map(defaultParams("dbtable") -> null))
    val ignoreSource = new DefaultSource(mockRedshift.jdbcWrapper, _ => mockS3Client)
    ignoreSource.createRelation(testSqlContext, SaveMode.Ignore, defaultParams, expectedDataDF)
    mockRedshift.verifyThatConnectionsWereClosed()
    mockRedshift.verifyThatExpectedQueriesWereIssued(Seq.empty)
  }

  test("Cannot save when 'query' parameter is specified instead of 'dbtable'") {
    val invalidParams = Map(
      "url" -> "jdbc:redshift://foo/bar",
      "tempdir" -> s3TempDir,
      "query" -> "select * from test_table")

    val e1 = intercept[IllegalArgumentException] {
      expectedDataDF.saveAsRedshiftTable(invalidParams)
    }
    assert(e1.getMessage.contains("dbtable"))
  }

  test("Public Scala API rejects invalid parameter maps") {
    val invalidParams = Map("dbtable" -> "foo") // missing tempdir and url

    val e1 = intercept[IllegalArgumentException] {
      expectedDataDF.saveAsRedshiftTable(invalidParams)
    }
    assert(e1.getMessage.contains("tempdir"))

    val e2 = intercept[IllegalArgumentException] {
      testSqlContext.redshiftTable(invalidParams)
    }
    assert(e2.getMessage.contains("tempdir"))
  }

  test("DefaultSource has default constructor, required by Data Source API") {
    new DefaultSource()
  }

  test("Saves throw error message if S3 Block FileSystem would be used") {
    val params = defaultParams + ("tempdir" -> defaultParams("tempdir").replace("s3n", "s3"))
    val e = intercept[IllegalArgumentException] {
      expectedDataDF.saveAsRedshiftTable(params)
    }
    assert(e.getMessage.contains("Block FileSystem"))
  }

  test("Loads throw error message if S3 Block FileSystem would be used") {
    val params = defaultParams + ("tempdir" -> defaultParams("tempdir").replace("s3n", "s3"))
    val e = intercept[IllegalArgumentException] {
      testSqlContext.read.format("com.databricks.spark.redshift").options(params).load()
    }
    assert(e.getMessage.contains("Block FileSystem"))
  }
}
