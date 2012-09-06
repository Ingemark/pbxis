#!/bin/sh

while true
  do curl localhost:58615/agent/$1
  echo
  sleep 1
done
