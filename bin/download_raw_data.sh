#!/bin/bash

# download the 120 GB rides
cat raw_data_urls.txt | xargs -n 1 -P 6 wget -c -P data/rides/

# download also the zone lookup table
LOOKUP_DATA_URL="https://s3.amazonaws.com/nyc-tlc/misc/taxi+_zone_lookup.csv"
echo $LOOKUP_DATA_URL | xargs -n 1 wget -c -P data/lookup/
# fix bad file name
mv "data/lookup/taxi+_zone_lookup.csv" data/lookup/taxi_zone_lookup.csv

