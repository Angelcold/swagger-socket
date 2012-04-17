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
package com.wordnik.swaggersocket.client

import scala.collection.JavaConversions._
import org.slf4j.{Logger, LoggerFactory}
import org.jfarcand.wcs.{TextListener, WebSocket}
import com.wordnik.swaggersocket.server.RequestMessage.Builder
import com.wordnik.swaggersocket.server.StatusMessage.Status
import com.wordnik.swaggersocket.server._
import java.util.concurrent.{ConcurrentHashMap, TimeoutException, TimeUnit, CountDownLatch}


object SwaggerSocket {

  def apply(timeoutInSeconds: Int): SwaggerSocket = {
    new SwaggerSocket("0", timeoutInSeconds, false, null, WebSocket())
  }

  def apply(): SwaggerSocket = {
    new SwaggerSocket("0", 30, false, null, WebSocket())
  }
}

case class SwaggerSocket(identity: String, timeoutInSeconds: Int, isConnected: Boolean, activeRequests: ConcurrentHashMap[String, Request], w: WebSocket) {

  val logger: Logger = LoggerFactory.getLogger(classOf[SwaggerSocket])
  val deserializer = new SwaggerSocketDeserializer
  val serializer = new SwaggerSocketSerializer
  var path: String = "ws://localhost"

  def open(request: Request): SwaggerSocket = {
    if (isConnected) throw new SwaggerSocketException(0, "Already Connected")

    val l = new CountDownLatch(1)
    var e: Option[Throwable] = None
    var serverIdentity = "0"
    var ws: WebSocket = null
    val handshake: Handshake = new Handshake.Builder()
      .queryString(request.getQueryString)
      .headers(request.getHeaders)
      .format(request.getDataFormat)
      .method(request.getMethod)
      .path(request.getPath)
      .body(request.getMessageBody)
      .build

    try {
      ws = w.open(handshake.getPath)
      ws.listener(new TextListener {

        override def onOpen {
          ws.send(serializer.serializeHandshake(handshake))
        }

        override def onClose {
          w.removeListener(this)
          l.countDown
        }

        override def onError(t: Throwable) {
          w.removeListener(this)
          e = Some(t);
          l.countDown
        }

        override def onMessage(message: String) {
          try {
            deserializer.deserializeStatus(message) match {
              case s: StatusMessage if (s.getStatus.getStatusCode < 400) =>
                serverIdentity = s.getIdentity
              case s: StatusMessage =>
                e = Some(new SwaggerSocketException(
                  s.getStatus.getStatusCode, s.getStatus.getReasonPhrase
                ))
              case _ => e = Some(
                new SwaggerSocketException(Status.NO_STATUS, "Null pointer")
              )
            }
          } catch {
            case ex: Throwable => logger.error("", ex)
          } finally {
            w.removeListener(this)
            l.countDown
          }
        }
      })

      path = handshake.getPath
    } catch {
      case t: Exception => e = Some(new SwaggerSocketException(0, t.getMessage))
    }

    if (!l.await(timeoutInSeconds, TimeUnit.SECONDS)) {
      throw new TimeoutException("Connect operation timed out after 30 seconds")
    }

    e.foreach(throw _)

    // Return a new instance with a unique identity
    new SwaggerSocket(serverIdentity, timeoutInSeconds, true, new ConcurrentHashMap[String, Request](), ws)

  }

  def close: SwaggerSocket = {
    w.close
    this
  }

  def send(r: Request): SwaggerSocket = {
    send(Array(r), null)
    this
  }

  def send(r: Request, l: SwaggerSocketListener): SwaggerSocket = {
    send(Array(r), l)
    this
  }

  def listener(l: SwaggerSocketListener): SwaggerSocket = {
    w.listener(new TextListener {

      override def onOpen {
        logger.trace("OnOpen " + this)
      }

      override def onClose {
        l.close
        logger.trace("onClose" + this)
      }

      override def onError(t: Throwable) {
        logger.error("", t)
        l.error(new SwaggerSocketException(500, t.getMessage))
      }

      /* will we get partial responses?  I think large messages can definitely be chunked */
      override def onMessage(message: String) {
        if (message.startsWith("{\"status\"")) {
          val s: StatusMessage = deserializer.deserializeStatus(message)
          l.error(new SwaggerSocketException(s.getStatus.getStatusCode, s.getStatus.getReasonPhrase))
        } else {
          val responses = deserializer.deserializeResponse(message)
          responses.foreach(response => {
            try {
              val rq: Request = activeRequests(response.getUuid);
              l.message(rq, response)
            } catch {
              case ex: Throwable => {
                logger.error("", ex)
                l.error(new SwaggerSocketException(500, ex.getMessage))
              }
            } finally {
              activeRequests -= response.getUuid
            }
          })
          l.messages(responses)
        }
      }
    })
    this
  }

  def send(r: Array[Request], l: SwaggerSocketListener): SwaggerSocket = {

    val requestMessage = new Builder().requests(r).identity(identity).build
    //	will we have race conditions here?
    r.foreach(request => {
      activeRequests += request.getUuid -> request
    })

    if (l != null) {
      w.listener(new TextListener {

        override def onOpen {
          w.removeListener(this)
          logger.trace("OnOpen " + this)
        }

        override def onClose {
          w.removeListener(this)
          l.close
        }

        override def onError(t: Throwable) {
          w.removeListener(this)
          l.error(new SwaggerSocketException(500, ""))
        }

        /* will we get partial responses?  I think large messages can definitely be chunked */
        override def onMessage(message: String) {
          if (message.startsWith("{\"status\"")) {
            val s: StatusMessage = deserializer.deserializeStatus(message)
            l.error(new SwaggerSocketException(s.getStatus.getStatusCode, s.getStatus.getReasonPhrase))
          } else {
            val responses = deserializer.deserializeResponse(message)
            responses.foreach(response => {
              // Is this response for us
              val rq: Request = activeRequests(response.getUuid);
              if (r.contains(rq)) {
                try {
                  l.message(rq, response)
                } catch {
                  case ex: Throwable => l.error(new SwaggerSocketException(500, ex.getMessage))
                } finally {
                  activeRequests -= response.getUuid
                }
              }
            })
            l.messages(responses)
          }
          w.removeListener(this)
        }
      })
    }
    w.send(serializer.serializeRequests(requestMessage))
    this
  }

  override def toString: String = {
    path
  }

}

