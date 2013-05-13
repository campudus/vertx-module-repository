#!/bin/bash

if [[ "" == $1 ]]; then
  echo 'You need to provide a password for the moderator. Please run'
  echo '    ./setup.sh <your_password_here>'

  return 1
fi

source .openshift/vertx-module-registry.config

mongo ${OPENSHIFT_MONGODB_DB_HOST}:${OPENSHIFT_MONGODB_DB_PORT}/${MONGO_DB_NAME} --eval "db.users.update({username:'approver'},{username:'approver',password:'${1}'},{upsert:true});"
