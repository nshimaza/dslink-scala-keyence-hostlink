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

import akka.Done
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Framing, GraphDSL, Keep, RestartSource, Sink, Source, Tcp, Zip}
import akka.util.ByteString
import org.dsa.iot.dslink.node.Permission
import org.dsa.iot.dslink.node.actions.table.Row
import org.dsa.iot.dslink.node.actions.{Action, ActionResult, Parameter}
import org.dsa.iot.dslink.node.value.{Value, ValueType}
import org.dsa.iot.dslink.{DSLink, DSLinkFactory, DSLinkHandler}
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._

object KeyenceHostLink {
  def main(args: Array[String]): Unit =
    DSLinkFactory.start(args, new KeyenceHostLinkDSLinkHandler())
}

class KeyenceHostLinkDSLinkHandler() extends DSLinkHandler {
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  private var runningPlcMap: Map[String, (UniqueKillSwitch, Future[Done])] = Map.empty
  private val log = LoggerFactory.getLogger(getClass)
  override val isResponder = true

  override def onResponderInitialized(link: DSLink): Unit = {
    log.info("KeyenceHostLinkDSLink initialized")

    val superRoot = link.getNodeManager.getSuperRoot
    superRoot.createChild("addPLC", true)
      .setDisplayName("Add PLC")
      .setAction(new Action(Permission.CONFIG, (event: ActionResult) => {
        val addPlcParams = for {
          name <- Right(event.getParameter("Name"))
            .flatMap(v => if (v.getType != ValueType.STRING) Left("Name must be String") else Right(v.getString))
            .flatMap(s => if (s == null || s == "") Left("Name must not be null") else Right(s))
          ipAddr <- Right(event.getParameter("PLC IP Address"))
            .flatMap(v => if (v.getType != ValueType.STRING) Left("PLC IP Address must be String") else Right(v.getString))
            .flatMap(s => if (s == null || s == "") Left("PLC IP Address must not be null") else Right(s))
          port <- Right(event.getParameter("PLC Port Number", new Value(8501)))
            .flatMap(v => if (v.getType != ValueType.NUMBER) Left("PLC Port Number must be number") else Right(v.getNumber.shortValue))
          devName <- Right(event.getParameter("Device Name", new Value("DM")))
            .flatMap(v => if (v.getType != ValueType.STRING) Left("Device Name must be String") else Right(v.getString))
            .flatMap(s => if (s == null || s == "") Left("Device Name must not be null") else Right(s))
          devAddr <- Right(event.getParameter("Start Address", new Value(0)))
            .flatMap(v => if (v.getType != ValueType.NUMBER) Left("Start Address must be number") else Right(v.getNumber.intValue))
          count <- Right(event.getParameter("Count", new Value(1)))
            .flatMap(v => if (v.getType != ValueType.NUMBER) Left("Count must be number") else Right(v.getNumber.intValue))
            .flatMap(i => if (i == 0) Left("Count must not be 0") else Right(i))
            .flatMap(i => if (i > 1000) Left("Count must not exceed 1000") else Right(i))
        } yield (name, ipAddr, port, devName, devAddr, count)
        addPlcParams match {
          case Right((name, ipAddr, port, devName, devAddr, count)) =>
            if (superRoot.getChild(name, true) != null) {
              event.getTable.addRow(Row.make(new Value(s"Fail: PLC $name already exists.")))
            } else {
              val plcNode = superRoot.createChild(name, true)
                .setAttribute("ipAddr", new Value(ipAddr))
                .setAttribute("port", new Value(port))
                .setAttribute("devName", new Value(devName))
                .setAttribute("devAddr", new Value(devAddr))
                .setAttribute("count", new Value(count))
                .build()
              plcNode.createChild("isRunning", true)
                .setValueType(ValueType.BOOL)
                .setValue(new Value(false))
                .build()
              plcNode.createChild("pollingInterval", true)
                .setValueType(ValueType.NUMBER)
                .setValue(new Value(0))
                .build()
              plcNode.createChild("Start Polling", true)
                .setAction(new Action(Permission.CONFIG, (event: ActionResult) => {
                  val plcNode = event.getNode.getParent
                  val startPollParams = for {
                    interval <- (runningPlcMap.get(plcNode.getName) match {
                      case None => Right(())
                      case Some(_) => Left("Polling already started")
                    })
                      .flatMap(_ => Right(event.getParameter("Interval (ms)", new Value(500)))
                        .flatMap(v => if (v.getType != ValueType.NUMBER) Left("Interval must be number") else Right(v.getNumber.intValue))
                        .flatMap(i => if (i < 10) Left("Interval must not be less than 10ms") else Right(i)))
                  } yield interval
                  startPollParams match {
                    case Right(interval) =>
                      val pollingStreamSource = Source.fromGraph(GraphDSL.create() { implicit builder =>
                        import GraphDSL.Implicits._
                        val in = Source.tick(1.second, interval.milliseconds, ByteString(s"RDS $devName$devAddr $count\r\n"))
                        val plc = Tcp().outgoingConnection("localhost", 8501)
                          .via(Framing.delimiter(ByteString("\r\n"), 30000))
                        val bcast = builder.add(Broadcast[ByteString](2, true))
                        val zip: FanInShape2[ByteString, ByteString, (ByteString, ByteString)] = builder.add(Zip[ByteString, ByteString])
                        // @formatter:off
                        in ~> bcast ~> plc ~> zip.in0
                              bcast ~>        zip.in1
                        // @formatter:on
                        SourceShape(zip.out)
                      })
                      val pollingStream = RestartSource.withBackoff(1.seconds, 60.seconds, 0.1) { () => pollingStreamSource }
                        .viaMat(KillSwitches.single)(Keep.right)
                        .toMat(Sink.foreach { response =>
                          val children = (devAddr until devAddr + count).map(i => plcNode.getChild(devName, true).getChild(i.toString, true))
                          val str = response._1.utf8String
                          plcNode.getChild("CSV", true).setValue(new Value(str.replace(' ', ',')))
                          str.split(' ').map(_.toInt).zip(children).foreach { t => t._2.setValue(new Value(t._1)) }
                        })(Keep.both).run()
                      runningPlcMap = runningPlcMap + (name -> pollingStream)
                      plcNode.getChild("isRunning", true).setValue(new Value(true))
                      plcNode.getChild("pollingInterval", true).setValue(new Value(interval))
                      event.getTable.addRow(Row.make(new Value("Success")))

                    case Left(err) => event.getTable.addRow(Row.make(new Value(err)))
                  }
                }).addParameter(new Parameter("Interval (ms)", ValueType.NUMBER, new Value(500))
                  .setDescription("Polling interval in millisecond"))
                  .addResult(new Parameter("Result", ValueType.STRING))
                )
                .build()
              plcNode.createChild("Stop Polling", true)
                .setAction(new Action(Permission.CONFIG, (event: ActionResult) => {
                  val plcNode = event.getNode.getParent

                  runningPlcMap.get(plcNode.getName) match {
                    case None => event.getTable.addRow(Row.make(new Value("Polling already stopped")))
                    case Some((killSwitch, _)) =>
                      killSwitch.shutdown()
                      runningPlcMap = runningPlcMap - name
                      plcNode.getChild("isRunning", true).setValue(new Value(false))
                      plcNode.getChild("pollingInterval", true).setValue(new Value(0))
                      event.getTable.addRow(Row.make(new Value("Success")))
                  }
                }).addResult(new Parameter("Result", ValueType.STRING))
                )
                .build()
              plcNode.createChild("Remove", true)
                .setAction(new Action(Permission.CONFIG, (event: ActionResult) => {
                  val isRunning = event.getNode.getParent.getChild("isRunning", true)
                  if (isRunning.getValue.getBool == true) {
                    event.getTable.addRow(Row.make(new Value("Stop Polling before remove")))
                  } else {
                    event.getNode.getParent.delete(true)
                    event.getTable.addRow(Row.make(new Value("Success")))
                  }
                }).addResult(new Parameter("Result", ValueType.STRING))
                )
                .build()
              val device = plcNode.createChild(devName, true).build()
              for (i <- devAddr until devAddr + count) {
                device.createChild(i.toString, true)
                  .setValueType(ValueType.NUMBER)
                  .setValue(new Value(0))
                  .build()
              }
              plcNode.createChild("CSV", true).setValueType(ValueType.STRING).setValue(new Value("")).build
              event.getTable.addRow(Row.make(new Value("Success")))
            }

          case Left(err) => event.getTable.addRow(Row.make(new Value(err)))
        }
      }).addParameter(new Parameter("Name", ValueType.STRING)
        .setDescription("Name of target PLC"))
        .addParameter(new Parameter("PLC IP Address", ValueType.STRING)
          .setDescription("IP address of target PLC"))
        .addParameter(new Parameter("PLC Port Number", ValueType.NUMBER, new Value(8501))
          .setDescription("TCP port number of target PLC")
          .setPlaceHolder("8501"))
        .addParameter(new Parameter("Device Name", ValueType.STRING, new Value("DM"))
          .setDescription("Device Name without address (e.g. DM)")
          .setPlaceHolder("DM"))
        .addParameter(new Parameter("Start Address", ValueType.NUMBER, new Value(0))
          .setDescription("Start Address of the device to read (e.g. 100)")
          .setPlaceHolder("0"))
        .addParameter(new Parameter("Count", ValueType.NUMBER, new Value(1))
          .setDescription("Number of contiguous devices to read from the Device")
          .setPlaceHolder("1"))
        .addResult(new Parameter("Result", ValueType.STRING))
      )
      .build()
  }

  override def onResponderConnected(link: DSLink): Unit = {
    log.info("KeyenceHostLinkDSLink connected")
  }
}
