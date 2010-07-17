#!/bin/sh

file=$1

if [ $# -lt 2 ]; then 
    commit=true
else
    commit=$2
fi

java -jar autodoc-standalone.jar --param-dir=params/$file --commit?=$commit
