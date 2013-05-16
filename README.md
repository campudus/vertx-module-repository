# Vert.x Module Registry

A small project to register modules.

## Dependencies

You need to have mongo db installed and Vert.x 2.0.

## Local installation

Before you can run the module registry, you need to create a user account. To do that, just create one with a simple query:

    mongo localhost:27017/moduleregistry --eval "db.users.update({username:'approver'},{username:'approver',password:'YOUR_PASSWORD_HERE'},{upsert:true});"

Just remember to change `localhost` to the host of your Mongo DB, `27017` is the port number and `moduleregistry` is the name of your database. `YOUR_PASSWORD_HERE` should be changed to whatever password you want to have.

When you created the user, you are good to go. Just start it with the following command:

    ./gradlew runMod

You can also start it with the regular vertx command line, but don't forget to build the module registry as a module first with `./gradlew copyMod`.

Please keep in mind that running the module registry without a configuration file won't let you send email notifications to the moderators. If you want to setup notifications via email, you need to check the [configuration](#configuration)

## Openshift installation

If you want to build/install this module on openshift, you can use the `./setup_approve_password.sh` script to set up the password for your user account right away. To set it to `my_secret_password`, just type this in rhc ssh console:

    ./setup_approve_password.sh my_secret_password

Uploading the module registry on openshift via git will fire some action hooks. These hooks should setup a small configuration file in your `$OPENSHIFT_DATA_DIR`. Please change the values according to your needs - if you had the mongodb gear available before, it will also try and fill in the correct values for you. If you want to notify moderators via email, you still need to change the [configuration](#configuration) file in `$OPENSHIFT_DATA_DIR/config.json`

## Configuration

There are a few configuration options available and a template for it can be found under `/src/main/resources/config.json`.

    {
      "host":"localhost",
      "port":8080,
      "database":{
        "host":"localhost",
        "port":27017,
        "db_name":"moduleregistry",
        "username":"admin",
        "password":"my_password"
      },
      "mailer":{
        "host":"smtp.googlemail.com",
        "port":465,
        "ssl":true,
        "auth":true,
        "username":"your.email@gmail.com",
        "password":"your_password",
        "infoMail":"modules@vertx.io",
        "moderators":["tim@wibble.com", "norman@wobble.com"]
      },
      "auth":{
        "user_collection":"users",
        "session_timeout": 1800000
      }
    }