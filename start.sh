#!/bin/bash
set -e
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi
mvn spring-boot:run
