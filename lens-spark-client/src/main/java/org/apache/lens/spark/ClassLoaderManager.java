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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

/**
 * Created by pranav.agarwal on 06/08/15.
 */
public class ClassLoaderManager extends URLClassLoader {

  //  @Getter
//  @Setter
  private ParentClassLoader parentClassLoader;

//  ClassLoaderManager(URL[] urls) {
//    super(urls, null);
//  }


  ClassLoaderManager(URL[] urls, ClassLoader parent) {
    super(urls, null);
    parentClassLoader = new ParentClassLoader(parent);
  }


  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    try {
      return super.loadClass(name, resolve);
    } catch (ClassNotFoundException e) {
      Class<?> clazz = parentClassLoader.loadClass(name, resolve);
      return clazz;
    }
  }

  @Override
  public URL getResource(String name) {
    URL url = super.findResource(name);
    if (url != null) {
      return url;
    } else {
      URL parentURL = parentClassLoader.getResource(name);
      return parentURL;
    }
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    Enumeration<URL> urls = super.findResources(name);
    if (urls != null && urls.hasMoreElements()) {
      return urls;
    } else {
      return parentClassLoader.getResources(name);
    }
  }

}
