This is a "spike" on a Shipkin-HTML-to-ePub convertor.
The code is of dubious quality and the output is not perfect.

> **NOTE:** You will need [HTML Tidy](https://www.html-tidy.org/) and
> [pdf2svg](http://www.cityinthesky.co.uk/opensource/pdf2svg/)
> installed in order to run the converter. Both tools can be
> installed on a Mac using `brew install`.
>
To generate an ePub, first create a zip archive of the course
content by running:

```bash
./gradlew zip -xcheckLinks -DpdfPresentations
```

in the relevant course directory (*not* in this project
directory).
This generates the zip file under the course `build/archive`
directory.

Build the convertor tool here (using the normal
`gradle build`) and then run the tool as follows:

```bash
java -jar build/libs/shipkin-html-epub-0.1.jar <course-zip-file> <course-title>
```
 
The first parameter is the course zip file name and the second
is the course title.
So, if you have generated the archive for the Core Spring
course and copied it to this directory, that would be:

```bash
java -jar build/libs/shipkin-html-epub-0.1.jar core-spring-course-1.10.0.zip 'Core Spring'
```

If all goes well this will create a `work` directory (deleting
any existing contents if the directory already exists).
Within it will be an ePub file with the same name as the zip
file and an `.epub` extension.
So, for the example given above that would be
`core-spring-course-1.10.0.epub`.

The ePub file can be validated with the
[epubcheck](https://github.com/w3c/epubcheck) tool:
```bash
java -jar epubcheck.jar --error work/core-spring-course-1.10.0.epub
```

The `--error` option suppresses warnings, of which there are
likely to be many, but there should be no errors or fatal messages.
