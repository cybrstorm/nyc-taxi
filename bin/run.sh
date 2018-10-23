#!/bin/bash

if [ "$1" == "" ]; then
    echo "need to provide the parameters:"
    echo "  1. Home folder of your local Spark 2.3 runtime, downloaded end extracted "
    echo "example:"
    echo "      ./$( basename $0 ) ~/Downloads/spark-2.3.2-bin-hadoop2.7/"
    exit 1
fi

SPARK_HOME=$1
export SPARK_HOME=$SPARK_HOME

echo "Running program for 'Taxi Experiment' ..."

./download_raw_data.sh

$SPARK_HOME/bin/spark-submit \
    --master=local[*] \
    --deploy-mode client \
    --driver-memory 2G \
    --executor-memory 2G \
    --conf "spark.driver.memoryOverhead=2G spark.executor.memoryOverhead=2G" \
    --class com.reuthlinger.taxi.TaxiExperiment \
    taxi-experiment-1.0.0-jar-with-dependencies.jar

echo "Program done"
# clean up a little
rm -rf spark-warehouse
mv results/*.csv results.csv
rm -rf results
mv rides-and-weather/*.csv rides-and-weather.csv
rm -rf rides-and-weather

echo "see results at 'results.csv' in this folder"
echo "listing cotents:"
cat results.csv

exit 0