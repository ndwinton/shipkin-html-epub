package com.vmware.edu

import groovy.io.FileType
import groovy.xml.XmlSlurper
import java.nio.file.Files

class Convertor {
    final static WORK_DIR = 'work'
    final static META_INF_DIR = 'work/META-INF'
    final static OEBPS_DIR = 'work/OEBPS'
    final static TEXT_DIR = 'work/OEBPS/Text'

    static void main(String[] args) {
        if (args.size() < 3) {
            System.err.println "Usage: Convertor shipkin-zip-file epub-title"
            System.exit(1)
        }

        def zipFile = args[0]
        def title = args[1]
        def epubFile = zipFile.replaceFirst(/\.zip$/, '.epub')
        args.toList().subList(2, args.size())
        def codebases = args.size() > 2 ? args.toList().subList(2, args.size()) : []

        println "Generating ePub '$epubFile' for '$title' from '$zipFile'"

        unzipSource(zipFile)
        def fileList = gatherFiles()
        tidyHtmlFiles(fileList)
        fixHtmlOrCssContent(fileList)
        // Fix non-relative URLS
        def idMappings = generateIdMappings(fileList)
        def remoteLinks = extractRemoteResourceLinks(fileList)
        def indexLinks = readIndexLinks()
        def uuid =  UUID.randomUUID().toString()

        generateOpfFile(title, uuid, idMappings, indexLinks, remoteLinks)
        makeTocAndOtherSupportFiles(title, uuid)
        makeMetaInf()
        makeMimeType()
        createEpubFile(epubFile)
    }

    static def unzipSource(String zipFile) {
        def ant = new AntBuilder()
        ant.with {
            delete(dir: WORK_DIR)
            mkdir(dir: TEXT_DIR)
            unzip(src: zipFile, dest: TEXT_DIR)
        }
    }

    static createEpubFile(String epubFilename) {
        // Unfortunately, we can't control the file ordering with the And zip
        // task, and the mimetype *has* to be first.
        new File(epubFilename).delete()
        def process = ["zip", "-r", "-X", epubFilename, "mimetype", "OEBPS", "META-INF"].execute(null, new File(WORK_DIR))
        println process.text
    }

    static List<File> gatherFiles() {
        def fileList = []
        new File(TEXT_DIR).traverse(type: FileType.FILES) { fileList << it }
        fileList
    }

    static void tidyHtmlFiles(List<File> fileList) {
        fileList.grep { it.name.endsWith('.html') }. each {
            tidyFile(it)
        }
    }

    static void tidyFile(File file) {
        def result = ['tidy',
                      '-asxhtml',
                      '-quiet',
                      '-modify',
                      '-clean',
                      '--wrap', '0',
                      '-utf8',
                      '--show-warnings', 'no',
                      '--add-xml-decl', 'yes',
                      file.path].execute()
        def errText = result.err.text
        if (errText != '') {
            System.err.println "$file.path\n$errText"
        }
    }

    static List<String> readIndexLinks() {
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
                opf.println """   <item id="remote.url.$index" href="$remote" media-type="${mediaTypeFromUrl(remote)}"/>"""
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
        if (contentType == null) {
            switch (name) {
                case ~/\.css$/:
                    contentType = 'text/css'
                    break
                case ~/\.js$/:
                    contentType = 'application/javascript'
                    break
                default:
                    contentType = 'text/plain'
            }
        }
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

    static String mediaTypeFromUrl(String url) {
        def mediaType = 'text/plain'
        def connection = new URL(url).openConnection()
        connection.requestMethod = 'HEAD'
        if (connection.responseCode == 200) {
            mediaType = connection.contentType
        }
        mediaType
    }

    static void makeTocAndOtherSupportFiles(String title, String uuid) {
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
        new File(META_INF_DIR).mkdirs()
        new File(META_INF_DIR, 'container.xml') << '''<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
   </rootfiles>
</container>'''
    }

    static def makeMimeType() {
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
        fileList.grep { it.name.endsWith('.html') || it.name.endsWith('.css') }.each { file ->
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
        line.replaceAll(/<svg/, '<svg xmlns="http://www.w3.org/2000/svg" ')
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
                .replaceAll('width="100%"', 'width="800"') // Not right, but not much else we can do
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
        // Embedded Google slides need to be PDF'ed
        line.replaceAll($/iframe src="https://docs.google.com/presentation/d/([^/]+)/embed"/$, 'iframe src="$1.pdf"')
        // Embedded PDFs can't appear as the target for the iframe and with a separate href!
            .replaceAll(/<p>This browser does not support PDFs.*/, '<p>This viewer does not support PDFs. Sigh.</p>')
            .replaceAll($/<a href=".*\.pdf">view</a>/$, '')
    }
}
