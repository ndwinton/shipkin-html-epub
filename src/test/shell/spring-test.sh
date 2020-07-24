#!/usr/local/bin/bash

groovy shipkin-html-epub/src/main/groovy/com/vmware/edu/Convertor.groovy \
  core-spring-course-1.10.0.zip \
  'Core Spring' \
  core-spring.epub \
  core-spring-labfiles.zip && \
java -jar epubcheck-4.2.4/epubcheck.jar --error work/core-spring-course-1.10.0.epub 2> check.out

