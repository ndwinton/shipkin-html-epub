package com.vmware.edu

import groovy.transform.CompileStatic

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

// See https://stackoverflow.com/questions/18800717/convert-text-content-to-image/18800845

@CompileStatic
class CoverImageGenerator {

    final static int PADDING = 20

    static void makeCover(String title, String version, String outputFile, String foreground, String background) {

        def titleFont = new Font("Arial", Font.BOLD, 48)
        def subFont = new Font("Arial", Font.PLAIN, 36)
        def lines = [title, '', 'VMware', '', 'Course version', version]

        def size = calculateTextImageSize(lines, titleFont)

        def img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().with {
            setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
            setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE)
            setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            font = titleFont
            color = new Color(parseHexString(background))
            fillRect(0, 0, img.width, img.height)
            color = new Color(parseHexString(foreground))
            lines.eachWithIndex { line, index ->
                drawString(line, PADDING, (fontMetrics.height * (index + 1)) + PADDING)
                font = subFont
            }
            dispose()
        }

        ImageIO.write(img, "png", new File(outputFile))
    }

    // Because font metrics is based on a graphics context, we need to create
    // a small, temporary image so we can ascertain the width and height
    // of the final image.
    //
    // We adjust the image size to a ratio of 4:3 (height:width) unless
    // it's already taller than that.

    private static Map<String,Integer> calculateTextImageSize(List<String> lines, Font textFont) {
        def img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Map<String,Integer> result = [:]
        img.createGraphics().with {
            font = textFont
            result.width = lines.collect { fontMetrics.stringWidth(it) }.max() + (PADDING * 2)
            result.height = Math.max((fontMetrics.height * lines.size()) + (PADDING * 2), (result.width * 1.333) as int)
            dispose()
        }
        result
    }

    private static int parseHexString(String hexString) {
        Integer.parseInt(hexString.replaceAll(/[^0-9a-fA-F]/, ''), 16)
    }
}