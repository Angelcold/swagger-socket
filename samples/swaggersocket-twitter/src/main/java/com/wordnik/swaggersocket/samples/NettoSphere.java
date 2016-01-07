/**
 *  Copyright 2016 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.wordnik.swaggersocket.samples;

import com.wordnik.swaggersocket.server.SwaggerSocketProtocolInterceptor;
import org.atmosphere.nettosphere.Config;
import org.atmosphere.nettosphere.Nettosphere;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class NettoSphere {

    private static final Logger logger = LoggerFactory.getLogger(Nettosphere.class);

    public static void main(String[] args) throws IOException {
        String key = null;
        String secret = null;
        if (args.length > 1) {
            key = args[0];
            secret = args[1];
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        if (key == null) {
            System.out.print("API-Key: ");
            key = br.readLine();
            System.out.print("API-Secret: ");
            secret = br.readLine();
        }

        int p = getHttpPort();
        Config.Builder b = new Config.Builder();
        b.resource("./app")
                .initParam("com.twitter.consumer.key", key)
                .initParam("com.twitter.consumer.secret", secret)
                .initParam("com.sun.jersey.config.property.packages", NettoSphere.class.getPackage().getName())
                .interceptor(new SwaggerSocketProtocolInterceptor())
                .port(p)
                .host("127.0.0.1")
                .build();
        Nettosphere s = new Nettosphere.Builder().config(b.build()).build();
        s.start();
        String a = "";

        logger.info("NettoSphere Twitter Search started on port {}", p);
        logger.info("Type quit to stop the server");
        while (!(a.equals("quit"))) {
            a = br.readLine();
        }
        System.exit(-1);
    }

    private static int getHttpPort() {
        String v = System.getProperty("nettosphere.port");
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // ignore;
            }
        }
        return 8080;
    }
}
