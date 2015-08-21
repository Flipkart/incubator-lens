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

import java.io.IOException;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;

import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Opportunity to manage the Request.
 */

public class RequestFilter implements ClientRequestFilter {
  private static final Configuration CONF = new Configuration(false);
  private static final String LENS_CLIENT_APP_NAME = "FDP-Lens-App-Name";
  private static final String LENS_CLIENT_APP_KEY = "FDP-Lens-Api-Key";

  private static Log LOG = LogFactory.getLog(RequestFilter.class);

  static {
    CONF.addResource("lens-client-site.xml");
  }
  private String getHeaderAppName() {
    return CONF.get(LENS_CLIENT_APP_NAME);
  }

  private String getHeaderAppKey() {
    return CONF.get(LENS_CLIENT_APP_KEY);
  }
  @Override
  public void filter(ClientRequestContext requestContext) throws IOException {
    // manage your request parameters here
    LOG.info(LENS_CLIENT_APP_NAME + " : " + getHeaderAppName());
    LOG.info(LENS_CLIENT_APP_KEY + " : " + getHeaderAppKey());
    requestContext.getHeaders().add(LENS_CLIENT_APP_NAME, getHeaderAppName());
    requestContext.getHeaders().add(LENS_CLIENT_APP_KEY, getHeaderAppKey());
  }
}
