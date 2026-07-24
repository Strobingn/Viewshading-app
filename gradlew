#!/bin/sh
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && pwd ) || exit
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi
exec "$JAVACMD" -Dorg.gradle.appname=Gradle -classpath "" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
