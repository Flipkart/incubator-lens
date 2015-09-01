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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.lens.api.query.*;
import org.apache.lens.client.exceptions.LensAPIException;
import org.apache.lens.client.exceptions.LensClientException;
import org.apache.lens.server.api.error.LensException;

import org.apache.commons.csv.*;
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

import org.relaxng.datatype.Datatype;

//TODO: refactor code so as not to use zeppeline as dependency.
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

    lensClientConfig.set(LENS_RESULT_OUTPUT_DIR_FORMAT_PARAM, "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' ESCAPED "
      + "BY '\\\\'");
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

  //TODO: check the mappings
  //TODO handle null values what should be the default.

  private Object typeCastScalaType(DataType type, String value) throws java.text.ParseException, LensException {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      switch (type.toString()) {
        case "BooleanType":
          return Boolean.parseBoolean(value);
        case "ShortType":
          return Short.parseShort(value);
        case "IntegerType":
          return Integer.parseInt(value);
        case "LongType":
          return Long.parseLong(value);
        case "FloatType":
          return Float.parseFloat(value);
        case "DoubleType":
          return Double.parseDouble(value);
        case "StringType":
          return value;
        case "TimestampType":
          return sdf.parse(value);
        case "BinaryType":
          // Properly handle binary type
          return value;
        case "DateType":
          return sdf.parse(value);
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
    QueryResult queryResult = resultSetWithStats.getResultSet().getResult();


    List<StructField> fields = new ArrayList<StructField>();
    for (ResultColumn resultColumn : resultSetWithStats.getResultSet().getResultSetMetadata().getColumns()) {
      String columnName;
      if (resultColumn.getName().contains(".")) {
        columnName = resultColumn.getName().split("\\.")[1];
      } else {
        columnName = resultColumn.getName();
      }
      fields.add(DataTypes.createStructField(columnName,
        getScalaDataType((resultColumn.getType())), true));
    }
    final List<StructField> schemaList = new ArrayList(fields);
    StructType schema = DataTypes.createStructType(fields);

    JavaRDD<Row> rowRDD = null;
    if(queryResult instanceof InMemoryQueryResult) {
      rowRDD = createRDDFromInMemoryResult((InMemoryQueryResult)queryResult);
    } else if (queryResult instanceof PersistentQueryResult){
      rowRDD = createRDDFromPersistedResult((PersistentQueryResult)queryResult, schemaList);
    } else {
      throw new LensException("Unsupported ResultSet Type");
    }

    return sqlContext.createDataFrame(rowRDD, schema);
  }

  private JavaRDD<Row> createRDDFromInMemoryResult(InMemoryQueryResult inMemoryQueryResult){
    List<ResultRow> resultRows = inMemoryQueryResult.getRows();
    List<List<Object>> objectList = new ArrayList();
    for(ResultRow row : resultRows){
      objectList.add(row.getValues());
    }
    JavaRDD<List<Object>> resultRowRDD = javaSparkContext.parallelize(objectList);
    JavaRDD<Row> rowRDD = resultRowRDD.map(new Function<List<Object>, Row>() {
      @Override
      public Row call(List<Object> list) throws Exception {
        Object[] objects = new Object[list.size()];
        int iterator=0;
        for(Object obj : list){
          objects[iterator++] = obj;
        }
        return RowFactory.create(objects);
      }
    });
    return rowRDD;
  }

  private JavaRDD<Row> createRDDFromPersistedResult(PersistentQueryResult persistentQueryResult, final
  List<StructField> scheamList){
    String uri = persistentQueryResult.getPersistedURI();
    LOG.info("RDD pointing to : " + uri);
    JavaRDD<String> javaRDD = javaSparkContext.textFile(uri);

    JavaRDD<Row> rowRDD = javaRDD.map(
      new Function<String, Row>() {
        public Row call(String record) throws Exception {
          CSVFormat c = CSVFormat.DEFAULT;
          List<CSVRecord> records;
          try {
            records = CSVParser.parse(record, c.withEscape('\\').withQuote((Character)null)).getRecords();
          } catch(Exception e) {
            throw new LensException("Error while parsing record: " + record, e);
          }

          // TODO: Handle NON CSV file also
          Object[] objects = new Object[records.get(0).size()];
          int i = 0;
          Iterator<String> iterator = records.get(0).iterator();
          Iterator<StructField> schemaIterator = scheamList.iterator();
          while (iterator.hasNext()) {
            String objectString = iterator.next();
            org.apache.spark.sql.types.DataType datatype = schemaIterator.next().dataType();
            if (isNull(objectString)) {
              objects[i++] = null;
            } else {
              try {
                objects[i++] = typeCastScalaType(datatype, objectString);
              } catch(ParseException e){
                throw new LensException("Error while formatting date: " + objectString + "dataType: " + datatype, e);
              } catch(LensException e) {
                throw new LensException("Error while typecasting: " + objectString + "dataType: " + datatype, e);
              }
            }
          }
          return RowFactory.create(objects);
        }
      });
    return rowRDD;
  }

  public QueryHandle createDataFrameAsync(String cubeQL) throws LensAPIException {
    return createDataFrameAsync(cubeQL, "");
  }

  public QueryHandle createDataFrameAsync(String cubeQL, String queryName) throws LensAPIException {
    return getLensClient().executeQueryAsynch(cubeQL, queryName).getData();
  }

  public DataFrame showDataBases(){
    List<String> nativeTables = getLensClient().getAllDatabases();;
    JavaRDD<String> distData = javaSparkContext.parallelize(nativeTables);
    List<StructField> fields = new ArrayList<StructField>();
    fields.add((DataTypes.createStructField("Name", DataTypes.StringType, true)));
    final List<StructField> schemaList = new ArrayList(fields);
    StructType schema = DataTypes.createStructType(fields);

    JavaRDD<Row> rowRDD = distData.map(new Function<String, Row>() {
      @Override
      public Row call(String s) throws Exception {
        return RowFactory.create(s);
      }
    });

    return sqlContext.createDataFrame(rowRDD, schema);
  }

  public String showCurrentDataBase(){
    return getLensClient().getCurrentDatabae();
  }

  public boolean setCurrentDataBase(String dbName){
    return getLensClient().setDatabase(dbName);
  }

  public DataFrame showNativeTables(){
    List<String> nativeTables = getLensClient().getAllNativeTables();
    JavaRDD<String> distData = javaSparkContext.parallelize(nativeTables);
    List<StructField> fields = new ArrayList<StructField>();
    fields.add((DataTypes.createStructField("Name", DataTypes.StringType, true)));
    final List<StructField> schemaList = new ArrayList(fields);
    StructType schema = DataTypes.createStructType(fields);

    JavaRDD<Row> rowRDD = distData.map(new Function<String, Row>() {
      @Override
      public Row call(String s) throws Exception {
        return RowFactory.create(s);
      }
    });

      return sqlContext.createDataFrame(rowRDD, schema);
  }

  public void killQuery(QueryHandle queryHandle) throws LensAPIException {
    getLensClient().killQuery(queryHandle);
  }

  public QueryStatus queryStatus(QueryHandle queryHandle) throws LensException {
    return getLensClient().getQueryStatus(queryHandle);
  }

  public DataFrame getDataFrame(QueryHandle queryHandle) throws LensException {
    if (!queryStatus(queryHandle).finished()) {
      throw new RuntimeException("query not yet finished " + queryHandle);
    }
    return (buildDataFrame(getLensClient().getAsyncResults(queryHandle)));

  }

  public String createHiveTable(DataFrame df, String table) throws LensException, HiveException {
    if (df == null || table == null) {
      throw new LensException("input can't be null");
    }

    //TODO checking of hive table's already existence.

    //TODO  check table's and path location don't already exist
    String tableName = this.namespace + "." + lensClientConfig.getUser().replace('.', '_') + "_" + table;
    String dataLocation = this.dataFramePersistenseDirectoryPath + tableName + UUID.randomUUID();
    try {
      //TODO: Check if the already existing file can be re-used. maybe if we can check df's parent is created from
      // TODO: queryresults?
      df.write().format("com.databricks.spark.csv").save(dataLocation);
    } catch (Exception e) {
      throw new LensException("Error while persisting DataFrame.", e);
    }

    LOG.info("Persisted DataFrame to " + dataLocation);
    System.out.println("Persisted DataFrame to " + dataLocation);
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
    sb.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' ESCAPED BY '\\\\' LOCATION '");
    sb.append(dataLocation);
    sb.append("'");

    String query = sb.toString();
    LOG.info("Table registration query: " + query);
    System.out.println("Table registration query: " + query);

    try {
      LensClient.LensClientResultSetWithStats resultSetWithStats = getLensClient().getResults(query,
        LENS_ML_TABLE_REGISTER_QUERY_NAME_PREFIX);
      if(resultSetWithStats.getQuery().getStatus().getStatus() != QueryStatus.Status.SUCCESSFUL){
        throw new LensException("Error while registering table to lens, Message: "+resultSetWithStats.getQuery().getErrorMessage());
      }
    } catch (LensAPIException ex) {
      throw new LensException("Error while registering table to lens", ex);
    }
    LOG.info("Finished saving DataFrame." + tableName);
    System.out.println("Finished saving DataFrame." + tableName);
    return tableName;
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

  //TODO: ability to set the user, else, always raise an exception
  //TODO: Create a relevant key name and app name for QAAS intg
  // TODO: create a package to be able to install on any server
  //TODO: Incase of an error on one line don't execute rest of the statements
  //TODO: setup LensContext variable similar to how sc is created
}