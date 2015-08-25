/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.client;

import java.io.Serializable;
import java.util.*;

import org.apache.lens.api.query.*;
import org.apache.lens.client.exceptions.LensAPIException;
import org.apache.lens.client.exceptions.LensClientException;
import org.apache.lens.server.api.error.LensException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;


public class SparkLensContext implements Serializable {

  private static final long serialVersionUID = 1L;

  private static final transient Log LOG = LogFactory.getLog(SparkLensContext.class);
  private static final Configuration CONF = new Configuration(false);
  //TODO user can also override these session parameters if we use client-site.xml
  private static final String LENS_PERSIST_RESULTSET_CONFIG_PARAM = "lens.query.enable.persistent.resultset";
  private static final String LENS_PERSIST_RESULTSET_DEFAULT_CONFIG = "false";
  private static final String LENS_PERSIST_RESULTSET_INDRIVER_CONFIG_PARAM = "lens.query.enable.persistent.resultset"
    + ".indriver";
  private static final String LENS_PERSIST_RESULTSET_INDRIVER_DEFAULT_CONFIG = "true";
  private static final String LENS_RESULT_OUTPUT_DIR_FORMAT_PARAM = "lens.query.result.output.dir.format";
  private static final String LENS_RESULT_OUTPUT_DIR_DEFAULT_FORMAT = "ROW FORMAT DELIMITED FIELDS TERMINATED BY ','";
  //private transient LensMLClient lensMLClient;
  private static final String LENS_ML_NAMESPACE_CONFIG_PARAM = "lens.ml.namespace";
  private static final String LENS_ML_NAMESPACE_DEFAULT_CONFIG = "lensml";
  private static final String LENS_ML_RESULT_PERSISTENCE_DIR_PARAM = "lens.ml.result.persistence.dir";
  private static final String LENS_ML_RESULT_PERSISTENCE_DEFAULT_DIR = "/tmp/lensreports/mlhdfsout";
  private static final String LENS_ML_DATAFETCH_QUERY_NAME_PREFIX = "ml_exploratory_datafetch_";
  private static final String LENS_ML_TABLE_REGISTER_QUERY_NAME_PREFIX = "ml_exploratory_table_registration_";
  private static final Integer RETRY_TIMES_LENS_CONNECTION = 3;
  private transient JavaSparkContext javaSparkContext; // Spark context //make it final
  private transient SQLContext sqlContext; //make it final
  private transient LensClient lensClient = null;
  private transient LensClientConfig lensClientConfig;
  private transient String namespace;
  private transient String dataFramePersistenseDirectoryPath;
  static {
    CONF.addResource("lens-client-site.xml");
  }

  public SparkLensContext(SparkContext sc, String user) {
    this(sc, new SQLContext(sc), user);
  }

  public SparkLensContext(SparkContext sc, SQLContext sqlContext, String user) {
    this(sc, sqlContext, new LensClientConfig(), user);
  }

  public SparkLensContext(SparkContext sc, SQLContext sqlContext, LensClientConfig lensClientConfig, String user) {
    javaSparkContext = new JavaSparkContext(sc);
    this.sqlContext = sqlContext;
    this.lensClientConfig = lensClientConfig;
    this.lensClientConfig.setUser(user);
    resetClientConfig(lensClientConfig);
    this.namespace = CONF.get(LENS_ML_NAMESPACE_CONFIG_PARAM, LENS_ML_NAMESPACE_DEFAULT_CONFIG);
    this.dataFramePersistenseDirectoryPath = lensClientConfig.get(LENS_ML_RESULT_PERSISTENCE_DIR_PARAM,
      LENS_ML_RESULT_PERSISTENCE_DEFAULT_DIR);

    //lensMLClient = new LensMLClient(lensClient);
    //TODO errors Caused by: java.lang.InstantiationException: org.apache.lens.server.api.ServiceProviderFactory

    LOG.info("SparkLensContext created");
  }

  public LensClient getLensClient() throws LensClientException {
    if (lensClient == null) {
      lensClient = new LensClient(lensClientConfig);
      return lensClient;
    }
    int tries = 0;
    while (tries < RETRY_TIMES_LENS_CONNECTION) {
      if (lensClient.isConnectionOpen()) {
        return lensClient;
      }
      tries++;
      LOG.error("Unable to connect " + lensClientConfig + " , trying again..");
      lensClient = new LensClient(lensClientConfig);
    }
    throw new LensClientException("Tried connecting to : " + lensClientConfig.toString() + " but no luck..");
  }

  private void resetClientConfig(LensClientConfig lensClientConfig) {

    lensClientConfig.set(LENS_RESULT_OUTPUT_DIR_FORMAT_PARAM, "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' ESCAPED BY '\\\\'");
    lensClientConfig.set(LENS_PERSIST_RESULTSET_CONFIG_PARAM, CONF.get(LENS_PERSIST_RESULTSET_CONFIG_PARAM,
      LENS_PERSIST_RESULTSET_DEFAULT_CONFIG));
    lensClientConfig.set(LENS_PERSIST_RESULTSET_INDRIVER_CONFIG_PARAM, CONF.get(
      LENS_PERSIST_RESULTSET_INDRIVER_CONFIG_PARAM, LENS_PERSIST_RESULTSET_INDRIVER_DEFAULT_CONFIG));
  }

  public DataFrame createDataFrame(String cubeQL) throws LensException {
    DataFrame df = createDataFrame(cubeQL, LENS_ML_DATAFETCH_QUERY_NAME_PREFIX + UUID.randomUUID());
    return df;
  }

  //TO DO: check the mappings
  private String getHiveTypeFromScalaType(DataType type) throws LensException {
    switch (type.toString()) {
    case "BooleanType":
      return "BOOLEAN";
    case "ShortType":
      return "SMALLINT";
    case "IntegerType":
      return "INT";
    case "LongType":
      return "BIGINT";
    case "FloatType":
      return "FLOAT";
    case "DoubleType":
      return "DOUBLE";
    case "StringType":
      return "STRING";
    case "TimestampType":
      return "TIMESTAMP";
    case "BinaryType":
      return "BINARY";
    case "DateType":
      return "DATE";
    default:
      throw new LensException("type not supported" + type.toString());
    }
  }

  boolean isNull(String value) {
    return value.matches("\\\\N");
  }

  //TO DO: check the mappings
  //TODO handle null values what should be the default.
  private Object typeCastScalaType(DataType type, String value) throws LensException {
    switch (type.toString()) {
    case "BooleanType":
      if (isNull(value)) {
        return Boolean.parseBoolean("true");
      }
      return Boolean.parseBoolean(value);
    case "ShortType":
      if (isNull(value)) {
        return Short.parseShort("true");
      }
      return Short.parseShort(value);
    case "IntegerType":
      if (isNull(value)) {
        return Integer.parseInt("0");
      }
      return Integer.parseInt(value);
    case "LongType":
      if (isNull(value)) {
        return Long.parseLong("0");
      }
      return Long.parseLong(value);
    case "FloatType":
      if (isNull(value)) {
        return Float.parseFloat("0.0");
      }
      return Float.parseFloat(value);
    case "DoubleType":
      if (isNull(value)) {
        return Double.parseDouble("0.0");
      }
      return Double.parseDouble(value);
    case "StringType":
      return value;
    case "TimestampType":
      if (isNull(value)) {
        return new Date();
      }
      return new Date(value);
    case "BinaryType":
      if (isNull(value)) {
        return Boolean.parseBoolean("true");
      }
      return Boolean.parseBoolean(value);
    case "DateType":
      if (isNull(value)) {
        return new Date();
      }
      return new Date(value);
    default:
      throw new LensException("type not supported" + type.toString());
    }
  }

  private DataType getScalaDataType(ResultColumnType resultColumnType) throws LensException {
    switch (resultColumnType) {
    case BOOLEAN:
      return DataTypes.BooleanType;
    case TINYINT:
    case SMALLINT:
      return DataTypes.ShortType;
    case INT:
      return DataTypes.IntegerType;
    case BIGINT:
      return DataTypes.LongType;
    case FLOAT:
      return DataTypes.FloatType;
    case DECIMAL:
    case DOUBLE:
      return DataTypes.DoubleType;
    case CHAR:
    case VARCHAR:
    case STRING:
      return DataTypes.StringType;
    case TIMESTAMP:
      return DataTypes.TimestampType;
    case BINARY:
      return DataTypes.BinaryType;
    case ARRAY:
    case STRUCT:
    case UNIONTYPE:
    case USER_DEFINED:
    case MAP:
    case NULL:
      return null;
    case DATE:
      return DataTypes.DateType;
    default:
      throw new LensException("type not supported" + resultColumnType.toString());
    }
  }

  public DataFrame createDataFrame(String cubeQL, String queryName) throws LensException {
    LensClient.LensClientResultSetWithStats resultSetWithStats;
    try {
      resultSetWithStats = getLensClient().getResults(cubeQL, queryName);
    } catch (LensAPIException ex) {
      throw new LensException("Error while executing query to lens", ex);
    }
    return buildDataFrame(resultSetWithStats);
  }

  private DataFrame buildDataFrame(LensClient.LensClientResultSetWithStats resultSetWithStats) throws LensException {

    String uri = ((PersistentQueryResult) resultSetWithStats.getResultSet().getResult()).getPersistedURI();

    JavaRDD<String> javaRDD = javaSparkContext.textFile(uri);
    LOG.info("RDD pointing to : " + uri);
    List<StructField> fields = new ArrayList<StructField>();
    for (ResultColumn resultColumn : resultSetWithStats.getResultSet().getResultSetMetadata().getColumns()) {
      String columnName;
      if (resultColumn.getName().contains(".")) {
        columnName = resultColumn.getName().replace('.', ':').split(":")[1];
      } else {
        columnName = resultColumn.getName();
      }
      fields.add(DataTypes.createStructField(columnName,
        getScalaDataType((resultColumn.getType())), true));
    }
    final List<StructField> scheamList = new ArrayList(fields);
    StructType schema = DataTypes.createStructType(fields);
    JavaRDD<Row> rowRDD = javaRDD.map(
      new Function<String, Row>() {
        public Row call(String record) throws Exception {
          List<CSVRecord> records = CSVParser.parse(record, CSVFormat.DEFAULT).getRecords();
          //Collection coll = records.get(0).toMap().values();
          //return RowFactory.create(coll.toArray(new Object[coll.size()]));
          // TODO: Handle NON CSV file also
          Object[] objects = new Object[records.get(0).size()];
          int i = 0;
          Iterator<String> iterator = records.get(0).iterator();
          Iterator<StructField> schemaIterator = scheamList.iterator();
          while (iterator.hasNext()) {
            objects[i++] = typeCastScalaType(schemaIterator.next().dataType(), iterator.next());
          }
          return RowFactory.create(objects);
        }
      });
    DataFrame df = sqlContext.createDataFrame(rowRDD, schema);

    // TODO: delete the persisted file;

    return df;
  }

  public QueryHandle createDataFrameAsync(String cubeQL) throws LensAPIException {
    return createDataFrameAsync(cubeQL, "");
  }

  public QueryHandle createDataFrameAsync(String cubeQL, String queryName) throws LensAPIException {
    return lensClient.executeQueryAsynch(cubeQL, queryName).getData();
  }

  public void killQuery(QueryHandle queryHandle) throws LensAPIException {
    lensClient.killQuery(queryHandle);
  }

  public QueryStatus queryStatus(QueryHandle queryHandle) throws LensException {
    return lensClient.getQueryStatus(queryHandle);
  }

  public DataFrame getDataFrame(QueryHandle queryHandle) throws LensException {
    if (!queryStatus(queryHandle).finished()) {
      throw new RuntimeException("query not yet finished " + queryHandle);
    }
    return (buildDataFrame(lensClient.getAsyncResults(queryHandle)));

  }

  public void createHiveTable(DataFrame df, String table) throws LensException, HiveException {
    if (df == null || table == null) {
      throw new LensException("input can't be null");
    }

    //TODO checking of hive table's already existence.

    //TODO  check table's and path location don't already exist
    String tableName = this.namespace + "." + lensClientConfig.getUser() + "_" + table;
    String dataLocation = this.dataFramePersistenseDirectoryPath + tableName + UUID.randomUUID();
    try {
      df.write().format("com.databricks.spark.csv").save(dataLocation);
    } catch (Exception e) {
      throw new LensException("Error while persisting DataFrame.", e);
    }

    LOG.info("Persisted DataFrame to " + dataLocation);

    StringBuffer sb = new StringBuffer();
    sb.append("CREATE EXTERNAL TABLE ");
    sb.append("`");
    sb.append(tableName);
    sb.append("`");
    StructType schema = df.schema();
    sb.append(" (");
    boolean firstPair = true;
    scala.collection.Iterator iter = schema.iterator();
    while (iter.hasNext()) {
      StructField structField = (StructField) iter.next();
      //TODO handle space in names surround by quotes
      if (!firstPair) {
        sb.append(", ");
      }
      sb.append("`");
      if (structField.name().contains(".")) {
        sb.append(structField.name().replace('.', ':').split(":")[1]);
      } else {
        sb.append(structField.name());
      }
      sb.append("`");
      sb.append(" ");
      sb.append(getHiveTypeFromScalaType(structField.dataType()));
      firstPair = false;
    }
    sb.append(") ");
    sb.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' LOCATION '");
    sb.append(dataLocation);
    sb.append("'");

    String query = sb.toString();
    LOG.info("Table registration query: " + query);

    try {
      LensClient.LensClientResultSetWithStats resultSetWithStats = getLensClient().getResults(query,
        LENS_ML_TABLE_REGISTER_QUERY_NAME_PREFIX);
    } catch (LensAPIException ex) {
      throw new LensException("Error while registering table to lens", ex);
    }
    LOG.info("Finished saving DataFrame.");
//    tbl = new Table();
//    for (int i = 0; i < df.schema().fields().length ; i++ ) {
//      tbl.getCols().add(new FieldSchema(df.schema().fields()[i].name(),
//        df.schema().fields()[i].dataType().typeName().toLowerCase(),"comments")); //TODO: get proper comments
//    }
//    tbl.setDataLocation();
//
//
//    hiveClient.createTable(tbl);
  }

  public void describeDataFrame(DataFrame df) {
//    df.describe("a");
//    df.stat();
  }

  //TODO : Close Lens session - shutdown hook
  //TODO : logs location?
  //TODO : test that can validate function signatures are same in LensContext also
  //TODO : Setup Zeppelin
  //TODO: Save to Hive Table
  //TODO: describe dataframe
  //TODO: Test on production spark cluster
  //TODO : SQL COntext will be able to query-- or user only needs to use LEnsCOntext?
  //TODO : Error needs to be propogated back to the user
  //TODO : Ability to connect and reconnect the session. Shouldn't be tied to the constructor
  //TODO: ability to set the user, else, always raise an exception
  //TODO: Create a relevant key name and app name for QAAS intg
  // TODO: create a package to be able to install on any server
  //TODO: Incase of an error on one line don't execute rest of the statements
  //TODO: setup LensContext variable similar to how sc is created
}
