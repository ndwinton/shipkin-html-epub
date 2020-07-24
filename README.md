This is a "spike" on a Shipkin-HTML-to-ePub convertor.
The code is of dubious quality and the output is not perfect.

To generate an ePub, first create a zip archive of the course
content by running:

```bash
./gradlew zip -xcheckLinks -DpdfPresentations
```

in the relevant course directory.
This generates the zip file under the course `build/archive`
directory.

Copy the zip file to this directory and then (after the normal
`gradle build` run the tool as follows:

```bash
java -jar build/libs/shipkin-html-epub-0.1.jar core-spring-course-1.10.0.zip 'Core Spring Course'
```

The first parameter is the course zip file name and the second
is the course title.
If all goes well this will create a `work` directory (deleting
any existing contents if the directory already exists).
Within it will be an ePub file with the same name as the zip
file and an `.epub` extension.

The ePub file can be validated with the
[epubcheck](https://github.com/w3c/epubcheck) tool:
```bash
java -jar epubcheck.jar --error work/core-spring-course-1.10.0.epub
```

The `--error` option suppresses warnings, of which there are
likely to be many!
