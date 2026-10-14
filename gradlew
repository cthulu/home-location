#!/bin/sh

##############################################################################
# Gradle start up script for UN*X
##############################################################################

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
fi

# Determine the script directory
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"

# Setup the classpath
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Execute Gradle
exec "$JAVACMD" \
  -Xmx64m \
  -Xms64m \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
