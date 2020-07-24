#!/usr/local/bin/bash

groovy shipkin-html-epub/src/main/groovy/com/vmware/edu/Convertor.groovy platform-acceleration-cnd-course-12.3.24.zip 'PAL for Java Developers' pal4devs.epub && \
java -jar epubcheck-4.2.4/epubcheck.jar --error platform-acceleration-cnd-course-12.3.24.epub 2> check.out
