package com.vmware.edu

import groovy.io.FileType
import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

import java.nio.file.Files

@Slf4j
@SpringBootApplication
class Convertor {
    final static WORK_DIR = 'build/epub'
    final static META_INF_DIR = WORK_DIR + '/META-INF'
    final static OEBPS_DIR = WORK_DIR + '/OEBPS'
    final static TEXT_DIR = OEBPS_DIR + '/Text'

    static void main(String[] args) {
        SpringApplication.run(Convertor, args)
    }

    @Bean
    CommandLineRunner runner() {
        return { argv -> execute(argv) }
    }

    static def execute(String[] args) {
        if (args.size() != 2) {
            log.error("Usage: ${Convertor.name} shipkin-zip-file epub-title")
            System.exit(1)
        }

        def zipFile = args[0]
        def title = args[1]
        def epubFile = zipFile.replaceFirst(/\.zip$/, '.epub').replaceFirst(/^.*\//, '')

        log.info "Generating ePub '$WORK_DIR/$epubFile' for '$title' from '$zipFile'"

        unzipSource(zipFile)
        def fileList = gatherFiles()
        fileList = convertAllPdfFilesToSvg(fileList)

        tidyHtmlFiles(fileList)
        fixHtmlOrCssContent(fileList)
        def idMappings = generateIdMappings(fileList)
        def remoteLinks = extractRemoteResourceLinks(fileList)
        def indexLinks = readIndexLinks()
        def uuid =  UUID.randomUUID().toString()

        generateOpfFile(title, uuid, idMappings, indexLinks, remoteLinks)
        makeTocAndOtherSupportFiles(title, uuid)
        makeMetaInf()
        makeMimeType()
        createEpubFile(epubFile)

        log.info("Generated '$epubFile'")
    }

    static def unzipSource(String zipFile) {
        log.info("Extracting course contents into '$WORK_DIR'")
        def ant = new AntBuilder()
        ant.with {
            delete(dir: WORK_DIR)
            mkdir(dir: TEXT_DIR)
            unzip(src: zipFile, dest: TEXT_DIR)
        }
    }

    static createEpubFile(String epubFilename) {
        log.info("Creating ePub archive")
        // Unfortunately, we can't control the file ordering with the Ant zip
        // task, and the mimetype *has* to be the first entry in the zip file
        // according to the ePub spec.
        new File(epubFilename).delete()
        runCommand(["zip", "-r", "-X", "-q", epubFilename, "mimetype", "OEBPS", "META-INF"], new File(WORK_DIR))
    }

    static List<File> gatherFiles() {
        def fileList = []
        new File(TEXT_DIR).traverse(type: FileType.FILES) { fileList << it }
        fileList
    }

    static void tidyHtmlFiles(List<File> fileList) {
        log.info("Converting files to XHTML format")
        fileList.grep { it.name.endsWith('.html') }. each {
            tidyFile(it)
        }
    }

    static void tidyFile(File file) {
        log.debug("Tidying $file")
        runCommand(['tidy',
                      '-asxhtml',
                      '-quiet',
                      '-modify',
                      '-clean',
                      '--wrap', '0',
                      '-utf8',
                      '--show-warnings', 'no',
                      file.path])
    }

    static List<String> readIndexLinks() {
        log.info("Reading top-level index links")
        def slurper = new XmlSlurper()
        slurper.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        def xhtml = slurper.parse(new File(TEXT_DIR + '/index.html'))
        def indexLinks = ['index.html']
        def indexes = xhtml.'**'.findAll { it['@class'] == 'index' }
        indexes.each { index ->
            index.'**'.findAll { it.name() == 'a' }.each { link ->
                def href = link.@href.text()
                if (!href.startsWith('http://') && !href.startsWith('https://')) {
                    indexLinks << href.replaceAll(/\//, '.')
                }
            }
        }
        indexLinks
    }

    static Map<String, String> generateIdMappings(List<File> fileList) {
        fileList.collectEntries {
            def embeddedPath = it.path - (OEBPS_DIR + '/')
            def idPath = it.path - (TEXT_DIR + '/')
            def id = idPath.replaceAll(/\//, '.')
            [(id):(embeddedPath)]
        }
    }

    static void generateOpfFile(String title, String uuid, Map idMapping, List indexLinks, List remoteLinks) {
        log.info("Generating OPF file")
        new File(OEBPS_DIR, 'content.opf').withPrintWriter('UTF-8') { opf ->
            opf.print """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
   <dc:identifier id="book-id">urn:uuid:$uuid</dc:identifier>
   <dc:title>$title</dc:title>
   <dc:language>en</dc:language>
   <dc:publisher>VMware</dc:publisher>
   <dc:creator>VMware</dc:creator>
   <dc:subject>$title</dc:subject>
   <dc:rights>© ${new Date().format('yyyy')} VMware Inc.</dc:rights>
   <dc:date>${new Date().format('yyyy-MM-dd')}</dc:date>
   <meta property="dcterms:modified">${new Date().format("yyyy-MM-dd'T'hh:mm:ss'Z'")}</meta>
  </metadata>
  <manifest>
   <item href="nav.xhtml" id="nav" media-type="application/xhtml+xml" properties="nav"/>
   <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
   <item id="unsupported" href="unsupported.xhtml" media-type="application/xhtml+xml"/>
"""
            idMapping.each { id, name ->
                def contentType = determineContentType(name)
                def properties = ''
                if (contentType == 'text/html') {
                    contentType = 'application/xhtml+xml'
                    properties = generatePropertiesForContent(name)
                }
                if (contentType == 'text/css') {
                    properties = generatePropertiesForContent(name)
                }
                if (contentType == 'application/zip') {
                    properties = 'fallback="unsupported"'
                }

                opf.println """   <item id="$id" href="$name" $properties media-type="$contentType"/>"""
            }
            remoteLinks.eachWithIndex { remote, index ->
                opf.println """   <item id="remote.url.$index" href="$remote" media-type="${contentTypeFromUrl(remote)}"/>"""
            }
            opf.print """  </manifest>
  <spine toc="ncx">
"""
            indexLinks.each { link ->
                opf.println """   <itemref idref="$link" linear="yes"/>"""
            }
            idMapping.each { id, name ->
                if (name =~ /(\.html|\.zip)/ && !indexLinks.contains(id)) {
                    opf.println """   <itemref idref="$id" linear="no"/>"""
                }
            }

            opf.print """  </spine>
</package>
"""
        }
    }

    static String determineContentType(name) {
        def contentType = Files.probeContentType(new File(OEBPS_DIR + '/' + name).toPath())
        if (contentType == null || contentType == 'text/plain') {
            switch (name) {
                case ~/.*\.css$/:
                    contentType = 'text/css'
                    break
                case ~/.*\.js$/:
                    contentType = 'application/javascript'
                    break
                default:
                    contentType = 'text/plain'
            }
        }
        log.debug("Content type for '$name' is '$contentType'")
        contentType
    }

    static String generatePropertiesForContent(String fileName) {
        def properties = []
        def text = new File(OEBPS_DIR + '/' + fileName).text
        if (text.contains('src="http') || text.contains('url(http')) {
            properties << 'remote-resources'
        }
        if (fileName.endsWith('.html')) {
            if (text.contains('<svg')) {
                properties << 'svg'
            }
            if (text.contains('<script')) {
                properties << 'scripted'
            }
        }
        if (properties) {
            return 'properties="' + properties.join(' ') + '"'
        }
        ''
    }

    static String contentTypeFromUrl(String url) {
        def contentType = 'text/plain'
        def connection = new URL(url).openConnection()
        connection.requestMethod = 'HEAD'
        if (connection.responseCode == 200) {
            contentType = connection.contentType
        }
        log.debug("Content type for '$url' is '$contentType'")
        contentType
    }

    static void makeTocAndOtherSupportFiles(String title, String uuid) {
        log.info("Generating TOC")
        new File(OEBPS_DIR, 'toc.ncx') << """<?xml version="1.0" encoding="utf-8"?>
<ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
  <head>
    <meta name="dtb:uid" content="urn:uuid:$uuid" />
    <meta name="dtb:depth" content="0" />
    <meta name="dtb:totalPageCount" content="0" />
    <meta name="dtb:maxPageNumber" content="0" />
  </head>
  <docTitle>
    <text>$title</text>
  </docTitle>
  <navMap>
    <navPoint id="navPoint-1" playOrder="1">
      <navLabel>
        <text>Main Page</text>
      </navLabel>
      <content src="Text/index.html" />
   </navPoint>
  </navMap>
</ncx>
"""

        new File(OEBPS_DIR, "nav.xhtml") << """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
    <head>
        <meta charset="UTF-8" />
        <title>$title</title>
    </head>
    <body>
        <nav epub:type="toc">
            <h1>$title</h1>
            <ol>
                <li><a href="Text/index.html">Main Index</a></li>
            </ol>
        </nav>
    </body>
</html>
"""

        new File(OEBPS_DIR, "unsupported.xhtml") << """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
    <head>
        <meta charset="UTF-8" />
        <title>Unsupported File Type</title>
    </head>
    <body>
        <h1>Unsupported File Type</h1>
        <p>Sorry, this file type isn't supported by your ePub reader.</p>
    </body>
</html>
"""
    }

    static void makeMetaInf() {
        log.info("Generating META-INF")
        new File(META_INF_DIR).mkdirs()
        new File(META_INF_DIR, 'container.xml') << '''<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
   </rootfiles>
</container>'''
    }

    static def makeMimeType() {
        log.info("Generating mimetype")
        new File(WORK_DIR, 'mimetype') << 'application/epub+zip'
    }

    static List<String> extractRemoteResourceLinks(List<File> fileList) {
        def remoteLinks = []
        fileList.grep { it.name.endsWith('.css') || it.name.endsWith('.html') }.each { file ->
            def lines = file.readLines()
            remoteLinks << lines.grep { it.matches(/.*src:.*url\(\s*http.*/) }.collect { it.replaceAll(/.*url\(\s*([^\)\s]+).*/, '$1') }
        }
        remoteLinks.flatten().toUnique()
    }

    static void fixHtmlOrCssContent(List<File> fileList) {
        log.info("Fixing HTML and CSS content")
        fileList.grep { it.name.endsWith('.html') || it.name.endsWith('.css') }.each { file ->
            log.debug("Fixing $file")
            def lines = file.readLines()
            def fixed = lines.collect { line ->
                expandImportLine(line)
            }.flatten().collect { line ->
                (Convertor::fixSvgElements >>
                        Convertor::fixFeedbackButton >>
                        Convertor::fixHiddenLabelTargets >>
                        Convertor::fixIframeAttributes >>
                        Convertor::fixTopLevelIndexRef >>
                        Convertor::fixCssErrors >>
                        Convertor::makeFooterInvisible >>
                        Convertor::makeSidebarInvisible >>
                        Convertor::fixEmbeddedPresentation)(line)
            }
            file.withWriter('UTF-8') { writer -> fixed.each { writer.writeLine(it) } }
        }
    }

    static String fixSvgElements(String line) {
        // Negative lookahead assertion in next line ensures we only add the namespace
        // if it is not already there
        line.replaceAll(/<svg (?!.*xmlns=)/, '<svg xmlns="http://www.w3.org/2000/svg" ')
                .replaceAll(/<svg(.*?)viewbox/, '<svg$1viewBox')
    }

    static String fixFeedbackButton(String line) {
        // <button> can not appear within an <a> element
        line.replaceAll($/<button>feedback</button>/$, 'feedback')
    }

    static Map urlContentCache = [:]

    static List<String> expandImportLine(String line) {
        if (line =~ $/^\s*@import\s+url/$) {
            def url = line.replaceFirst($/^\s*@import\s+url\('/$, '').replaceFirst(/'\);\s*$/, '')
            urlContentCache[url] ?= url.toURL().readLines()
        } else {
            [line]
        }
    }

    static String fixHiddenLabelTargets(String line) {
        line.replaceFirst($/<input type="text" name="(lunch|break)-end"/$, '<input type="text" id="$1-end" name="$1-end"')
    }

    static String fixIframeAttributes(String line) {
        line.replaceAll('frameborder="0"', '')
                .replaceAll(/width="100%"\s+height="569"/, 'style="width: 100%;" height="600"') // Not ideal, but seems to work
                .replaceAll('mozallowfullscreen="true"', '')
                .replaceAll('webkitallowfullscreen="true"', '')
                .replaceAll('allowfullscreen="true"', 'allowfullscreen="allowfullscreen"')
    }

    static String fixTopLevelIndexRef(String line) {
        line.replaceAll($/<a ([^>]*)href="((\.\./?)+)">/$, '<a $1href="$2/index.html">')
    }

    static String fixCssErrors(String line) {
        // This is really a Shipkin bug - fixed on master
        line.replaceAll(/;;/, ';')
    }

    static String makeFooterInvisible(String line) {
        line.replaceAll(/footer\s*\{/, 'footer { display: none;')
    }

    static String makeSidebarInvisible(String line) {
        line.replaceAll(/.sidebar-container\s+\{/, '.sidebar-container { display: none;')
    }

    static String fixEmbeddedPresentation(String line) {
        line.replaceFirst(/<object data="([^"]+).pdf" type="application\/pdf"(.*)>/, "<iframe src=\"\$1-main.html\" \$2>")
            //Abitrarily removing or replacing <embed> and <object> tags isn't great, but probably OK in the
            // limited context of Shipkin-generated HTML
            .replaceFirst(/<\/?embed[^>]*>/, '')
            .replaceFirst(/<\/object>/, '</iframe>')
            .replaceAll(/<p>This browser does not support PDFs.*/, '')
            .replaceAll($/<a href=".*\.pdf">view</a>/$, '')
    }

    static List<File> convertAllPdfFilesToSvg(List<File> fileList) {
        fileList.collect { File file ->
            if (file.path.endsWith('.pdf')) {
                convertPdfToSvg(file.path)
            } else {
                file
            }
        }.flatten() as List<File>
    }

    static List<File> convertPdfToSvg(String filename) {
        log.info("Converting '$filename' to SVG format")

        log.debug("Reducing PDF resolution")
        def tempPdfFile = filename + '-X.pdf'
        runCommand([
                "gs",
                "-sDEVICE=pdfwrite",
                "-dCompatibilityLevel=1.4",
                "-dPDFSETTINGS=/screen", // replacing /screen with /ebook increases image quality at the cost of size
                "-dNOPAUSE",
                "-dQUIET",
                "-dBATCH",
                "-sOutputFile=${tempPdfFile}",
                filename
        ])

        def base = filename.replaceFirst(/\.pdf$/, '')
        runCommand(["pdf2svg", tempPdfFile, "${base}-%03d.svg", "all"])

        def mainFile = new File("${base}-main.html")
        def generatedFiles = [mainFile]
        mainFile.withPrintWriter('UTF-8') { out ->
            out.print """<html>
 <head>
  <title>Converted PDF Presentation</title>
 </head>
 <body>
"""
            int index = 1
            def possibleSvgFile = new File(possibleSvgFileName(base, index))
            while (possibleSvgFile.exists()) {
                out.println("  <div class=\"svg-slide\"><img src=\"${possibleSvgFile.name}\" style=\"width: 100%;\"/><hr/></div>")
                generatedFiles << possibleSvgFile
                index++
                possibleSvgFile = new File(possibleSvgFileName(base, index))
            }
            out.println """ </body>
</html>
"""
            log.debug("Deleting '$filename'")
            new File(filename).delete()
            new File(tempPdfFile).delete()
        }
        generatedFiles
    }

    private static String possibleSvgFileName(String base, int index) {
        sprintf("%s-%03d.svg", base, index)
    }

    static runCommand(List<String> args, File workingDir = null) {
        def process = args.execute(null as List, workingDir)

        // See https://stackoverflow.com/questions/10688688/an-error-equivalent-for-process-text
        def (output, error) = new StringWriter().with { o -> // For the output
            new StringWriter().with { e ->                     // For the error stream
                process.waitForProcessOutput( o, e )
                [ o, e ]*.toString()                             // Return them both
            }
        }
        if (output) {
            log.info(output)
        }
        if (error) {
            log.error(error)
        }
    }
}
