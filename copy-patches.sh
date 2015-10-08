#!/bin/bash

mkdir -pv /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-core/1.2.0.redhat-621048
mkdir -pv /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-commands/1.2.0.redhat-621048
mkdir -pv /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-management/1.2.0.redhat-621048
mkdir -pv /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-features/1.2.0.redhat-621048

cp -v patch/patch-core/target/patch-core-1.2.0.redhat-621048.jar /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-core/1.2.0.redhat-621048/patch-core-1.2.0.redhat-621048.jar
cp -v patch/patch-commands/target/patch-commands-1.2.0.redhat-621048.jar /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-commands/1.2.0.redhat-621048/patch-commands-1.2.0.redhat-621048.jar
cp -v patch/patch-management/target/patch-management-1.2.0.redhat-621048.jar /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-management/1.2.0.redhat-621048/patch-management-1.2.0.redhat-621048.jar
cp -v patch/patch-features/target/classes/patch-features.xml /opt/servers/jboss-fuse-6.2.0.redhat-133/system/io/fabric8/patch/patch-features/1.2.0.redhat-621048/patch-features-1.2.0.redhat-621048-features.xml
