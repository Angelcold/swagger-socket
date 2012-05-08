/**
 *  Copyright 2012 Wordnik, Inc.
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
package com.wordnik.swaggersocket.server;

import com.wordnik.swaggersocket.protocol.Handshake;
import com.wordnik.swaggersocket.protocol.HandshakeMessage;
import com.wordnik.swaggersocket.protocol.Message;
import com.wordnik.swaggersocket.protocol.Request;
import com.wordnik.swaggersocket.protocol.StatusMessage;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.HeaderConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SwaggerSocketProtocolHttpSupport implements AtmosphereInterceptor {

    private final static String DELEGATE_HANDSHAKE = SwaggerSocketProtocol.class.getName() + ".delegateHandshake";
    private final static String SWAGGERSOCKET_REQUEST = Request.class.getName();
    private final static String SWAGGER_SOCKET_DISPATCHED = "request.dispatched";
    private final SwaggerSocketResponseFilter serializer;

    private static final Logger logger = LoggerFactory.getLogger(SwaggerSocketProtocol.class);
    private final ObjectMapper mapper;
    private boolean delegateHandshake = false;

    public SwaggerSocketProtocolHttpSupport() {
        this.mapper = new ObjectMapper();
        serializer = new SwaggerSocketResponseFilter(mapper);
    }

    @Override
    public Action inspect(AtmosphereResource r) {

        final AtmosphereRequest request = r.getRequest();
        if (request.getHeader("SwaggerSocket") != null
                && request.getAttribute(SWAGGER_SOCKET_DISPATCHED) == null && r.transport() != AtmosphereResource.TRANSPORT.WEBSOCKET) {

            AtmosphereResponse response = r.getResponse();
            response.setContentType("application/json");

            // Suspend to keep the connection OPEN.
            if (request.getMethod() == "GET") {
                request.getSession().setAttribute("PendingResource", r);
                r.suspend();
                return Action.SUSPEND;
            }

            AtmosphereFramework framework = r.getAtmosphereConfig().framework();
            response.asyncIOWriter(new AsyncIOWriterAdapter() {
                @Override
                public AsyncIOWriter redirect(AtmosphereResponse r, String location) throws IOException {
                    return this;
                }

                @Override
                public AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
                    Request swaggerSocketRequest =
                            Request.class.cast(r.request().getAttribute(SwaggerSocketResponseFilter.SWAGGERSOCKET_REQUEST));

                    if (swaggerSocketRequest == null) {
                        logger.debug("Handshake mapping (could be expected) {} : {}", errorCode, message);
                        return this;
                    }
                    return this;
                }

                @Override
                public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
                    String s = serializer.filter(r, data);
                    AtmosphereResource resource = (AtmosphereResource) request.getSession().getAttribute("PendingResource");
                    if (resource != null) {
                        resource.getResponse().getOutputStream().write(s.getBytes());
                        resource.resume();
                        r.flushBuffer();
                    } else {
                        r.write(s);
                    }
                    return this;
                }

                @Override
                public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
                    byte s[] = serializer.filter(r, data);
                    AtmosphereResource resource = (AtmosphereResource) request.getSession().getAttribute("PendingResource");
                    if (resource != null) {
                        resource.getResponse().getOutputStream().write(s);
                        resource.resume();
                        r.flushBuffer();
                    } else {
                        r.write(s);
                    }
                    return this;
                }

                @Override
                public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
                    byte s[] = serializer.filter(r, data, offset, length);
                    AtmosphereResource resource = (AtmosphereResource) request.getSession().getAttribute("PendingResource");
                    if (resource != null) {
                        resource.getResponse().getOutputStream().write(s);
                        resource.resume();
                        r.flushBuffer();
                    } else {
                        r.write(s);
                    }
                    return this;
                }

                @Override
                public void close(AtmosphereResponse r) throws IOException {
                }

                @Override
                public AsyncIOWriter flush(AtmosphereResponse r) throws IOException {
                    return this;
                }
            });

            StringBuilder d = new StringBuilder();
            try {
                InputStreamReader isr = new InputStreamReader(request.getInputStream());
                BufferedReader bufReader = new BufferedReader(isr);
                char[] charBuffer = new char[8192];

                for (int readCount = bufReader.read(charBuffer); readCount > -1; readCount = bufReader.read(charBuffer)) {
                    d.append(charBuffer, 0, readCount);
                }

                String data = d.toString();
                AtomicBoolean handshakeDone = (AtomicBoolean) request.getSession().getAttribute("swaggersocket.handshakeDone");

                logger.debug(data);
                List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();
                if (handshakeDone == null || !handshakeDone.get()) {
                    HandshakeMessage handshakeMessage = mapper.readValue(data, HandshakeMessage.class);

                    // We have got a valid Handshake message, so we are ready to serve resource.
                    handshakeDone = new AtomicBoolean(true);
                    Handshake handshake = handshakeMessage.getHandshake();
                    String identity = UUID.randomUUID().toString();

                    request.getSession().setAttribute("swaggersocket.handshakeDone", handshakeDone);
                    request.getSession().setAttribute("swaggersocket.identity", identity);
                    request.setAttribute("swaggersocket.handshake", identity);


                    StatusMessage statusMessage = new StatusMessage.Builder().status(new StatusMessage.Status(200, "OK"))
                            .identity(identity).build();
                    response.getOutputStream().write(mapper.writeValueAsBytes(statusMessage));

                    if (!delegateHandshake) {
                        return Action.CANCELLED;
                    } else {
                        list.add(SwaggerSocketProtocol.toAtmosphereRequest(request, handshake, false));
                    }
                } else {
                    Message swaggerSocketMessage = mapper.readValue(data, Message.class);

                    String identity = (String) request.getSession().getAttribute("swaggersocket.identity");

                    if (!swaggerSocketMessage.getIdentity().equals(identity)) {
                        StatusMessage statusMessage = new StatusMessage.Builder().status(new StatusMessage.Status(503, "Not Allowed"))
                                .identity(identity).build();
                        response.getOutputStream().write(mapper.writeValueAsBytes(statusMessage));
                        return Action.CANCELLED;
                    }

                    List<Request> requests = swaggerSocketMessage.getRequests();
                    for (Request req : requests) {
                        list.add(SwaggerSocketProtocol.toAtmosphereRequest(request, req, requests.size() > 1));
                    }
                }

                for (AtmosphereRequest ar : list) {
                    try {
                        ar.setAttribute(SWAGGER_SOCKET_DISPATCHED, "true");

                        framework.doCometSupport(ar, response.request(ar).destroyable(false));
                    } catch (ServletException e) {
                        logger.warn("", e);
                        return Action.CANCELLED;
                    }
                }
                response.flushBuffer();
                return Action.CANCELLED;
            } catch (IOException e) {
                logger.warn("", e);
                return Action.CONTINUE;
            }

        }
        return Action.CONTINUE;
    }
}
