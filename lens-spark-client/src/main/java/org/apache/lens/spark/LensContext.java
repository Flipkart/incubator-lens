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
package org.apache.lens.spark;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.lens.api.query.QueryHandle;
import org.apache.lens.client.exceptions.LensAPIException;
import org.apache.lens.server.api.error.LensException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.SQLContext;

import lombok.Getter;

public class LensContext {

  /**
   * The Constant DEFAULT_USER_NAME.
   */
  public static final String DEFAULT_USER_NAME = "anonymous";
  private static final Log LOG = LogFactory.getLog(LensContext.class);
  private static final Configuration CONF = new Configuration(false);
  private static final String LENS_ADD_JARS_PARAM = "lens.spark.add.jars";
  private static ClassLoader sLoader = null;
  private static Pattern ignoreJarFilePattern = Pattern.compile("^lens-.*|^org.apache.lens_lens.*");
  private static Pattern includeJarFilePattern = Pattern.compile(".*lens-ml-lib.*|.*lens-client.*");
  @Getter
  private Object lensContext = null;

//  private static List<Pattern> ignoreJarFilePatternList = new ArrayList<Pattern>() {
//    {
//      add(Pattern.compile("^lens.*"));
//      add(Pattern.compile("^org.apache.lens_lens.*"));
//    }
//  };
  @Getter
  private SparkContext sc = null;
  @Getter
  private SQLContext sqlContext = null;
  @Getter
  private String user;

  static {
    CONF.addResource("lens-client-site.xml");
  }
  public LensContext(SparkContext sc, String user) {
    this(sc, new SQLContext(sc), user);
  }

  public LensContext(SparkContext sc, SQLContext sqlContext, String user) {
    this(sc, sqlContext, getListOfJarFiles(sc), user);
  }


  public LensContext(SparkContext sc, SQLContext sqlContext, List<String> jarFiles, String user) {
    this.sc = sc;
    this.sqlContext = sqlContext;
    this.user = user;
    int i = 1;
    LOG.info("Inside LensContext Constructor " + i);
    createNewClassLoader(jarFiles);
  }

  public static ClassLoader getClassLoader() {
    return sLoader;
  }

  private static List<String> getListOfJarFiles(SparkContext sc) {
    if (CONF.get(LENS_ADD_JARS_PARAM) != null) {
      return Arrays.asList(CONF.get(LENS_ADD_JARS_PARAM).split(","));
    }
    if (sc.jars() == null) {
      return null;
    }
    List<String> jarsList = new LinkedList<String>();

    for (String filePath : scala.collection.JavaConversions.seqAsJavaList(sc.jars())) {
      URI uri;
      String jarPath;
      try {
        uri = new URI(filePath);
      } catch (Exception e) {
        LOG.warn("Ignoring " + filePath, e);
        continue;
      }
      if (!(uri.getScheme().equals("file"))) {
        LOG.warn("Ignoring " + filePath + " , as " + uri.getScheme() + " is not supported");
        continue;
      }
      jarPath = filePath.substring(5);
      File f = new File(jarPath);
      if (ignoreJarFilePattern.matcher(f.getName()).find()) {
        if (includeJarFilePattern.matcher(f.getName()).find()) {
          LOG.info("Found " + f.getAbsolutePath());
        } else {
          LOG.info("LensContext ignoring to load : " + f.getAbsolutePath());
          continue;
        }
      }
      jarsList.add(jarPath);
    }
    return jarsList;
  }

  private void createNewClassLoader(List<String> jarfiles) {
    List<URL> urlList = new ArrayList<URL>();
    for (String jarPath : jarfiles) {
      try {
        File f = new File(jarPath);
        URI uri = f.getAbsoluteFile().toURI();
        File foo = new File(uri.getPath());
        if (!foo.exists()) {
          LOG.error("****** ERROR FILE DOESN'T EXIST ********* :" + f.getAbsolutePath());
          continue;
        }
        urlList.add(foo.toURI().toURL());
        //System.out.println("Loading jar: "+);
      } catch (MalformedURLException e) {
        LOG.error("****** ERROR UNABLE TO LOAD ********* : " + jarPath, e);
      }
    }
    synchronized (this) {
      if (sLoader == null) {
        sLoader = new ClassLoaderManager(urlList.toArray(new URL[urlList.size()]),
          org.apache.spark.util.Utils.getContextOrSparkClassLoader());
        //Thread.currentThread().getContextClassLoader());
      }
    }
    Thread.currentThread().setContextClassLoader(sLoader);

    Class<?> claszz = null;
    Object instance;
    try {
      claszz = Thread.currentThread().getContextClassLoader().loadClass("org.apache.lens.client.SparkLensContext");
    } catch (ClassNotFoundException e) {
      LOG.error("org.apache.lens.client.LensContext not found", e);
    }

    try {
      Constructor c = claszz.getConstructor(SparkContext.class, SQLContext.class, String.class);
      instance = (Object) c.newInstance(this.sc, this.sqlContext, this.user);
      lensContext = instance;
    } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
      LOG.error("org.apache.lens.client.LensContext not initialized", e);
    }
  }

  public DataFrame createDataFrame(String cubeQL) throws LensException{
    try {
      Thread.currentThread().setContextClassLoader(sLoader);
      Class<?> claszz =
        Thread.currentThread().getContextClassLoader().loadClass("org.apache.lens.client.SparkLensContext");
      Method m = claszz.getDeclaredMethod("createDataFrame", String.class);
      return (DataFrame) m.invoke(lensContext, cubeQL);
    } catch (Exception e) {
      LOG.error("Error Creating DataFrame, while reflection call");
      throw new LensException(e);
    }
  }

  public QueryHandle createDataFrameAsync(String cubeQL) throws LensAPIException {
    try {
      Thread.currentThread().setContextClassLoader(sLoader);
      Class<?> claszz =
        Thread.currentThread().getContextClassLoader().loadClass("org.apache.lens.client.SparkLensContext");
      Method m = claszz.getDeclaredMethod("createDataFrameAsync", String.class);
      return (QueryHandle) m.invoke(lensContext, cubeQL);
    } catch (Exception e) {
      LOG.error(e.getMessage());
      return null;
    }
  }

  //TODO it should take only table name string instead of dataset. Since we are restricting output database.
  public void createHiveTable(DataFrame df, String table) throws HiveException {

    try {
      Thread.currentThread().setContextClassLoader(sLoader);
      Class<?> claszz =
        Thread.currentThread().getContextClassLoader().loadClass("org.apache.lens.client.SparkLensContext");
      Method m = claszz.getDeclaredMethod("createHiveTable", DataFrame.class, String.class);
      m.invoke(lensContext, df, table);
    } catch (Exception e) {
      LOG.error(e.getMessage());
    }

  }
}
