#!/usr/bin/env sh
##############################################################################
# Minimal Gradle wrapper script for this sample project.
##############################################################################

DIR="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
MAIN_CLASS=org.gradle.wrapper.GradleWrapperMain

# Use JAVA_HOME if set, otherwise fall back to `java` on PATH
if [ -n "$JAVA_HOME" ] ; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE=java
fi

exec "$JAVA_EXE" -cp "$CLASSPATH" "$MAIN_CLASS" "$@"
