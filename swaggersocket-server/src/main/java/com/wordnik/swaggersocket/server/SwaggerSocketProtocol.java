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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class SwaggerSocketProtocol implements WebSocketProtocol {

    private final static String DELEGATE_HANDSHAKE = SwaggerSocketProtocol.class.getName() + ".delegateHandshake";
    private final static String SWAGGERSOCKET_REQUEST = Request.class.getName();

    private static final Logger logger = LoggerFactory.getLogger(SwaggerSocketProtocol.class);
    private final ObjectMapper mapper;
    private boolean delegateHandshake = false;

    public SwaggerSocketProtocol() {
        mapper = new ObjectMapper();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(DELEGATE_HANDSHAKE);
        if (s != null) {
            delegateHandshake = Boolean.parseBoolean(s);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.resource().suspend(-1, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(WebSocket webSocket) {
        AtmosphereResource resource = webSocket.resource();
        if (resource != null) {
            resource.getBroadcaster().removeAtmosphereResource(resource);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        if (t.response() != null) {

            Request swaggerSocketRequest =
                    Request.class.cast(t.response().getRequest().getAttribute(SWAGGERSOCKET_REQUEST));

            if (swaggerSocketRequest == null) {
                logger.debug("Handshake mapping (could be expected) {} : {}", t.response().getStatus(), t.response().getStatusMessage());
                return;
            }

            logger.debug("Unexpected status code {} : {}", t.response().getStatus(), t.response().getStatusMessage());
            StatusMessage statusMessage = new StatusMessage.Builder()
                    .status(new StatusMessage.Status(t.response().getStatus(),
                            t.response().getStatusMessage()))
                    .identity(swaggerSocketRequest.getUuid()).build();
            try {
                byte[] b = mapper.writeValueAsBytes(statusMessage);
                webSocket.write(b, 0, b.length);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String data) {
        AtmosphereResource resource = webSocket.resource();

        AtomicBoolean handshakeDone = (AtomicBoolean) resource.getRequest().getAttribute("swaggersocket.handshakeDone");

        try {
            logger.debug(data);
            List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();

            if (handshakeDone == null || !handshakeDone.get()) {
                HandshakeMessage handshakeMessage = mapper.readValue(data, HandshakeMessage.class);

                // We have got a valid Handshake message, so we are ready to serve resource.
                handshakeDone = new AtomicBoolean(true);
                Handshake handshake = handshakeMessage.getHandshake();
                String identity = UUID.randomUUID().toString();

                resource.getRequest().setAttribute("swaggersocket.handshakeDone", handshakeDone);
                resource.getRequest().setAttribute("swaggersocket.identity", identity);

                StatusMessage statusMessage = new StatusMessage.Builder().status(new StatusMessage.Status(200, "OK"))
                        .identity(identity).build();
                webSocket.write(mapper.writeValueAsBytes(statusMessage));

                if (!delegateHandshake) {
                    return null;
                } else {
                    list.add(toAtmosphereRequest(resource.getRequest(), handshake));
                }
            } else {
                Message swaggerSocketMessage = mapper.readValue(data, Message.class);

                String identity = (String) resource.getRequest().getAttribute("swaggersocket.identity");

                if (!swaggerSocketMessage.getIdentity().equals(identity)) {
                    StatusMessage statusMessage = new StatusMessage.Builder().status(new StatusMessage.Status(503, "Not Allowed"))
                            .identity(identity).build();
                    webSocket.write(mapper.writeValueAsBytes(statusMessage));
                    return null;
                }

                List<Request> requests = swaggerSocketMessage.getRequests();
                for (Request r : requests) {
                    list.add(toAtmosphereRequest(resource.getRequest(), r));
                }
            }
            return list;
        } catch (IOException e) {
            logger.error("Invalid SwaggerSocket Message {}. Message will be ignored", data);
            logger.debug("parseMessage", e);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
        // TODO: Not good performance wise.
        return onMessage(webSocket, new String(data, offset, length));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean inspectResponse() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String handleResponse(AtmosphereResponse res, String message) {
        if (invalidState((Request) res.getRequest().getAttribute(SWAGGERSOCKET_REQUEST))) {
            logger.error("Protocol error. Handshake not occurred yet!");
            return message;
        }

        try {
            return mapper.writeValueAsString(wrapMessage(res, message));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] handleResponse(AtmosphereResponse res, byte[] message, int offset, int length) {
        if (invalidState((Request) res.getRequest().getAttribute(SWAGGERSOCKET_REQUEST))) {
            logger.error("Protocol error. Handshake not occurred yet!");
            byte[] copy = new byte[length - offset];
            System.arraycopy(message, 0, copy, offset, length);
            return copy;
        }

        try {
            return mapper.writeValueAsBytes(wrapMessage(res, new String(message, offset, length, res.getCharacterEncoding())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseMessage wrapMessage(AtmosphereResponse res, String message) {
        Response.Builder builder = new Response.Builder();

        builder.body(message)
                .status(res.getStatus(), res.getStatusMessage());

        Map<String, String> headers = res.headers();
        for (String s : headers.keySet()) {
            builder.header(new Header(s, headers.get(s)));
        }

        Request swaggerSocketRequest =
                Request.class.cast(res.getRequest().getAttribute(SWAGGERSOCKET_REQUEST));

        builder.uuid(swaggerSocketRequest.getUuid()).method(swaggerSocketRequest.getMethod());
        String identity = (String) res.getRequest().getAttribute("swaggersocket.identity");

        return new ResponseMessage(identity, builder.build());
    }

    private boolean invalidState(Request swaggerSocketRequest) {
        return swaggerSocketRequest == null ? true : false;
    }

    private AtmosphereRequest toAtmosphereRequest(HttpServletRequest r, ProtocolBase request) {
        AtmosphereRequest.Builder b = new AtmosphereRequest.Builder();

        Map<String, String> headers = new HashMap<String, String>();
        if (request.getHeaders() != null) {
            for (Header h : request.getHeaders()) {
                headers.put(h.getName(), h.getValue());
            }
        }

        Map<String, String[]> queryStrings = new HashMap<String, String[]>();
        if (request.getQueryString() != null) {
            for (QueryString h : request.getQueryString()) {
                String[] s = queryStrings.get(h.getName());
                if (s != null) {
                    String[] s1 = new String[s.length];
                    System.arraycopy(s, 0, s1, 0, s.length);
                    s1[s.length] = h.getValue();
                    queryStrings.put(h.getName(), s1);
                } else {
                    queryStrings.put(h.getName(), new String[]{h.getValue()});
                }
            }
        }

        String p = request.getPath().replaceAll("\\s+", "%20").trim();
        String requestURL = r.getRequestURL() + p;
        if (r.getRequestURL().toString().endsWith("/") && p.startsWith("/")) {
            requestURL = r.getRequestURL().toString() + p.substring(1);
        }

        String requestURI = r.getRequestURI() + p;
        if (r.getRequestURI().endsWith("/") && p.startsWith("/")) {
            requestURI = r.getRequestURI() + p.substring(1);
        }

        b.pathInfo(p)
                .contentType(headers.get("Content-Type"))
                .headers(headers)
                .method(request.getMethod())
                .queryStrings(queryStrings)
                .requestURI(requestURI)
                .requestURL(requestURL)
                .request(r)
                .dispatchRequestAsynchronously(true)
                .body(request.getMessageBody() != null ? request.getMessageBody().toString() : "");

        AtmosphereRequest ar = b.build();
        ar.setAttribute(SWAGGERSOCKET_REQUEST, request);
        return ar;
    }

}
