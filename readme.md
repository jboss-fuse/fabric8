fabric8: an open source integration platform (iPaaS)
================================

Welcome to [fabric8](http://fabric8.io/), the open source integration platform for running all your open source integration technologies, like Apache ActiveMQ, Camel, CXF and Karaf in the cloud.

Get Started
--------

* [Getting Started Guide](http://fabric8.io/gitbook/getStarted.html) for how to run fabric8 on your local computer
* [Run fabric8 on OpenShift](https://www.openshift.com/quickstarts/jboss-fuse-61) for using fabric8 on the open hybrid cloud. More details [here](https://github.com/jboss-fuse/fuse-openshift-cartridge/blob/master/README.md)
* [Run fabric8 on docker](https://github.com/fabric8io/fabric8-docker#try-it-out)
* Run fabric8 or Fuse on an OpenStack node, if you want to add the insight-console to a container, you first need to do the following
  1. Set the environment variable OPENSTACK_FLOATING_IP to the floating IP of that node
  2. The fabric.environment system property has to be set to "openstack" at startup.
Demos
-----

* <a href="https://vimeo.com/80625940">demo of using fabric8 in JBoss Fuse on OpenShift</a></p>
* <a href="https://vimeo.com/album/2635012">more JBoss Fuse and JBoss A-MQ demos</a>


Building the code
--------------

Please see the [readme-build.md](readme-build.md) file.
