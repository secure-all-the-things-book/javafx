#!/usr/bin/env bash
ls -la pom.xml  && ./mvnw -DskipTests -Pnative native:compile || echo "couldn't find pom.xml, please ensure you're running from the root of your JavaFX project"