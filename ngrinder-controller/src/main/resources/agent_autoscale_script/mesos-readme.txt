MESOS related environment setup:

1. The mesos version of the mesos cluster should be compatible with the OS on which ngrinder controller running, because mesos framework launched in ngrinder controller will load the dynamic library (libmesos.x.xx.x.so)

2. Pay attention to set environment MESOS_NATIVE_JAVA_LIBRARY or MESOS_NATIVE_LIBRARY with the libmesos.x.xx.x.so file path before startup ngrinder controller application, else the autoscale feature based on mesos can't work.

3. The mesos software should be launched with "docker" as part of the configuration of "--containerizers" in slave machine of the cluster.