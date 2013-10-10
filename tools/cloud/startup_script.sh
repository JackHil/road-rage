#!/bin/bash
# Startup script each instance executes to setup packages and launch the script to run experiments.

GS=gs://aorta/`hostname`

# Install java and scala
sudo apt-get update
sudo apt-get install -y openjdk-6-jre libjansi-java
wget http://scala-lang.org/files/archive/scala-2.10.2.deb
sudo dpkg -i scala-2.10.2.deb

# Grab the AORTA package
gsutil cp gs://aorta/aorta.tgz .
mkdir aorta
cd aorta
tar xzf ../aorta.tgz
./tools/cloud/upload_gs.sh ${GS}-a-status 'Compiling'
./recompile

# Run the experiment
for iter in a b c d e
do
  #./tools/analyze_routes cloud ${GS}-${iter}-
  #./tools/analyze_externality cloud ${GS}-${iter}-
  ./tools/tradeoff_experiment cloud ${GS}-${iter}-
  ./tools/cloud/upload_gs.sh ${GS}-${iter}-status 'Done'
done

# When we're done, shutdown
gcutil deleteinstance --force `hostname`
