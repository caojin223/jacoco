JaCoCo Java Code Coverage Library
=================================

[![Build Status](https://dev.azure.com/jacoco-org/JaCoCo/_apis/build/status/JaCoCo?branchName=master)](https://dev.azure.com/jacoco-org/JaCoCo/_build/latest?definitionId=1&branchName=master)
[![Build status](https://ci.appveyor.com/api/projects/status/g28egytv4tb898d7/branch/master?svg=true)](https://ci.appveyor.com/project/JaCoCo/jacoco/branch/master)
[![Maven Central](https://img.shields.io/maven-central/v/org.jacoco/jacoco.svg)](http://search.maven.org/#search|ga|1|g%3Aorg.jacoco)

JaCoCo is a free Java code coverage library distributed under the Eclipse Public
License. Check the [project homepage](http://www.jacoco.org/jacoco)
for downloads, documentation and feedback.

Please use our [mailing list](https://groups.google.com/forum/?fromgroups=#!forum/jacoco)
for questions regarding JaCoCo which are not already covered by the
[extensive documentation](http://www.jacoco.org/jacoco/trunk/doc/).

Note: We do not answer general questions in the project's issue tracker. Please use our [mailing list](https://groups.google.com/forum/?fromgroups=#!forum/jacoco) for this.
-------------------------------------------------------------------------

mvn spotless:apply
"C:\Program Files\Java\jdk1.8.0_321\bin\java" -agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005,suspend=y -javaagent:org.jacoco.agent/target/classes/jacocoagent.jar=includes=cn.devops.*,output=tcpclient,port=9999,address=localhost,append=true -jar org.jacoco.agent/target/product_management-3.0.jar
"C:\Program Files\Java\jdk1.8.0_321\bin\java" -agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005,suspend=y -javaagent:org.jacoco.agent/target/org.jacoco.agent-0.8.9-SNAPSHOT.jar=includes=cn.devops.*,output=tcpclient,port=9999,address=localhost,append=true -jar org.jacoco.agent/target/product_management-3.0.jar