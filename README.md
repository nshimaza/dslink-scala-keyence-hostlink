# KeyenceHostLinkDSLink
DSLink acquiring data via Host Link protocol of Keyence KV-7500 or EtherNet/IP modules

## Build
You need [sbt](https://www.scala-sbt.org/) to build.

```shell-session
git clone https://github.com/nshimaza/dslink-scala-keyence-hostlink.git
cd dslink-scala-keyence-hostlink
sh dist.sh
```

You will get dslink-java-keyence-hostlink.zip.  You can install it as a DSLink zip distribution via EFM System
Administrator.

## Usage

This DSLink is configurable via EFM System Administrator GUI or Data Flow Editor or DGLux server GUI.

You can add Keyence PLCs by giving its name, IP address, listening port (default is 8501), device name to read start
address of the device to read, number of sequential addresses to read.  Once you successfully added PLC, you can start
polling with polling interval, stop polling, and remove the PLC configuration.
