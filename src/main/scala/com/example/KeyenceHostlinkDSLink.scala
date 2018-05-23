/**
 *
 * Copyright (c) 2018 Cisco and/or its affiliates.
 *
 * This software is licensed to you under the terms of the Cisco Sample
 * Code License, Version 1.0 (the "License"). You may obtain a copy of the
 * License at
 *
 *                https://developer.cisco.com/docs/licenses
 *
 * All use of the material herein must be in accordance with the terms of
 * the License. All rights not expressly granted by the License are
 * reserved. Unless required by applicable law or agreed to separately in
 * writing, software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 */

package com.example

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Framing, RestartSource, Sink, Source, Tcp}
import akka.util.ByteString
import org.dsa.iot.dslink.node.value.{Value, ValueType}
import org.dsa.iot.dslink.{DSLink, DSLinkFactory, DSLinkHandler}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object KeyenceHostLinkDSLink {
  def main(args: Array[String]): Unit =
    DSLinkFactory.start(args.drop(3), new KeyenceHostLinkDSLinkHandler(args(0), args(1), args(2).toInt))
}

class KeyenceHostLinkDSLinkHandler(address: String, device: String, count: Int) extends DSLinkHandler {
  private val log = LoggerFactory.getLogger(getClass)
  override val isResponder = true

  override def onResponderInitialized(link: DSLink): Unit = log.info("KeyenceHostLinkDSLink initialized")

  override def onResponderConnected(link: DSLink): Unit = {
    log.info("KeyenceHostLinkDSLink connected")

    val node = link.getNodeManager.getSuperRoot
      .createChild(device, true)
      .setDisplayName(s"$device ($count)")
      .setValueType(ValueType.STRING)
      .setValue(new Value(""))
      .build

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()


    RestartSource.withBackoff(1.seconds, 60.seconds, 0.1) { () =>
      Source.tick(1.second, 500.milliseconds, ByteString(s"RDS $device $count\r\n"))
        .via(Tcp().outgoingConnection(address, 8501))
        .via(Framing.delimiter(ByteString("\r\n"), 30000))
    }
      .to(Sink.foreach(s => node.setValue(new Value(s.utf8String))))
      .run()
  }
}
