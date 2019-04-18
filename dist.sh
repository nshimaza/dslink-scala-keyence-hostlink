#!/bin/sh
rm -fr target
sbt assembly
rm -fr dslink-java-keyence-hostlink
mkdir -p dslink-java-keyence-hostlink/bin
mkdir -p dslink-java-keyence-hostlink/lib
cp dslink.json dslink-java-keyence-hostlink/
cp dslink-scala-keyence-hostlink dslink-java-keyence-hostlink/bin/
cp target/scala-2.12/dslink-scala-keyence-hostlink-assembly-0.1.0-SNAPSHOT.jar dslink-java-keyence-hostlink/lib
rm dslink-java-keyence-hostlink.zip
zip -r dslink-java-keyence-hostlink.zip dslink-java-keyence-hostlink
