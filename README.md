# Vert.x Module Registry

A small project to register modules.

## Dependencies

You need to have mongo db installed and Vert.x 2.0.

## Local installation

Before you can run the module registry, you need to create a user account. To do that, just create one with a simple query:

    mongo localhost:27017/default_db --eval "db.users.update({username:'approver'},{username:'approver',password:'YOUR_PASSWORD_HERE'},{upsert:true});"

Just remember to change `localhost` to the host of your Mongo DB, `27017` is the port number and `default_db` is the name of your database. `YOUR_PASSWORD_HERE` should be changed to whatever password you want to have.

When you created the user, you are good to go. Just start it with the following command:

    ./gradlew runMod

You can also start it with the regular vertx command line, but don't forget to build the module registry as a module first with `./gradlew copyMod`

## Openshift installation

If you want to build/install this module on openshift, you can use the `./setup_approve_password.sh` script to set up the password for your user account right away. To set it to `my_secret_password`, just type this in rhc ssh console:

    ./setup_approve_password.sh my_secret_password
