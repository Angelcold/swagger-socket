package com.wordnik.swaggersocket.server;

import com.wordnik.swaggersocket.protocol.Header;
import com.wordnik.swaggersocket.protocol.Request;
import com.wordnik.swaggersocket.protocol.Response;
import com.wordnik.swaggersocket.protocol.ResponseMessage;
import com.wordnik.swaggersocket.protocol.StatusMessage;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


final class SwaggerSocketResponseFilter implements WebSocketResponseFilter {
    private static final Logger logger = LoggerFactory.getLogger(SwaggerSocketResponseFilter.class);
    public final static String SWAGGERSOCKET_REQUEST = Request.class.getName();
    private final ObjectMapper mapper;

    public SwaggerSocketResponseFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String filter(AtmosphereResponse r, String message) {

        // This is the handshake, nothing to do.
        if (r.request() == null || r.request().getAttribute("swaggersocket.handshake") != null) {
            return message;
        }

        if (invalidState((Request) r.request().getAttribute(SWAGGERSOCKET_REQUEST))) {
            logger.error("Protocol error. Handshake not occurred yet!");
            StatusMessage statusMessage = new StatusMessage.Builder().status(new StatusMessage.Status(501, "Protocol error. Handshake not occurred yet!"))
                    .identity("0").build();
            try {
                return mapper.writeValueAsString(statusMessage);
            } catch (IOException e) {
                return "";
            }
        }

        try {
            ResponseMessage m = wrapMessage(r, message);
            if (m != null) {
                return mapper.writeValueAsString(m);
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] filter(AtmosphereResponse r, byte[] message) {
        return filter(r, message, 0, message.length);
    }

    @Override
    public byte[] filter(AtmosphereResponse r, byte[] message, int offset, int length) {

        // This is the handshake, nothing to do.
        if (r.request() == null || r.request().getAttribute("swaggersocket.handshake") != null) {
            return message;
        }

        if (invalidState((Request) r.request().getAttribute(SWAGGERSOCKET_REQUEST))) {
            logger.error("Protocol error. Handshake not occurred yet!");
            StatusMessage statusMessage = new StatusMessage.Builder().status(new StatusMessage.Status(501, "Protocol error. Handshake not occurred yet!"))
                    .identity("0").build();
            try {
                return mapper.writeValueAsBytes(statusMessage);
            } catch (IOException e) {
                return new byte[0];
            }
        }

        try {
            ResponseMessage m = wrapMessage(r, new String(message, offset, length, r.request().getCharacterEncoding()));
            if (m != null) {
                return mapper.writeValueAsBytes(m);
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean invalidState(Request swaggerSocketRequest) {
        return swaggerSocketRequest == null ? true : false;
    }

    protected final static ResponseMessage wrapMessage(AtmosphereResponse res, String message) {
        Response.Builder builder = new Response.Builder();

        builder.body(message)
                .status(res.getStatus(), res.getStatusMessage());

        Map<String, String> headers = res.headers();
        for (String s : headers.keySet()) {
            builder.header(new Header(s, headers.get(s)));
        }

        Request swaggerSocketRequest =
                Request.class.cast(res.request().getAttribute(SWAGGERSOCKET_REQUEST));

        builder.uuid(swaggerSocketRequest.getUuid()).method(swaggerSocketRequest.getMethod())
                .path(swaggerSocketRequest.getPath());
        String identity = (String) res.request().getAttribute("swaggersocket.identity");

        AtomicInteger expectedResponseCount = (AtomicInteger) res.request().getAttribute("ResponseCountNumber");
        ResponseMessage m = null;
        if (expectedResponseCount != null) {
            m = (ResponseMessage) res.request().getAttribute(ResponseMessage.class.getName());
            if (m != null) {
                m.response(builder.build());
            } else {
                m = new ResponseMessage(identity, builder.build());
            }

            if (expectedResponseCount.decrementAndGet() == 0) {
                return m;
            } else {
                res.request().setAttribute(ResponseMessage.class.getName(), m);
                return null;
            }
        }

        if (m == null) {
            m = new ResponseMessage(identity, builder.build());
        }
        return m;
    }
}
