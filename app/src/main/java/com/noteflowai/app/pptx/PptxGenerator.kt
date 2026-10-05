package com.noteflowai.app.pptx

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SlideData(
    val title: String,
    val bullets: List<String> = emptyList(),
    val content: List<SlideBlock> = emptyList(),
    val isTitleSlide: Boolean = false
)

sealed class SlideBlock {
    data class Bullet(val text: String) : SlideBlock()
    data class Code(val code: String) : SlideBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : SlideBlock()
}

object PptxGenerator {

    fun generatePptx(slides: List<SlideData>, title: String, outputFile: File): File {
        FileOutputStream(outputFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                writeEntry(zos, "[Content_Types].xml", contentTypesXml(slides.size))
                writeEntry(zos, "_rels/.rels", relsXml())
                writeEntry(zos, "ppt/presentation.xml", presentationXml(slides.size))
                writeEntry(zos, "_rels/presentation.xml.rels", presentationRels(slides.size))
                writeEntry(zos, "ppt/theme/theme1.xml", themeXml())
                writeEntry(zos, "ppt/slideLayouts/slideLayout1.xml", slideLayoutXml())
                writeEntry(zos, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRels())
                writeEntry(zos, "ppt/slideMasters/slideMaster1.xml", slideMasterXml())
                writeEntry(zos, "ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRels())
                writeEntry(zos, "docProps/app.xml", appXml(title))
                writeEntry(zos, "docProps/core.xml", coreXml(title))

                for (i in slides.indices) {
                    val slideNum = i + 1
                    writeEntry(zos, "ppt/slides/slide$slideNum.xml", slideXml(slides[i], slideNum))
                    writeEntry(zos, "ppt/slides/_rels/slide$slideNum.xml.rels", slideRels())
                }
            }
        }
        return outputFile
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    // --- XML Templates ---

    private fun contentTypesXml(slideCount: Int): String {
        val slideOverrides = (1..slideCount).joinToString("\n") {
            """  <Override PartName="/ppt/slides/slide$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml" />"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml" />
  <Default Extension="xml" ContentType="application/xml" />
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml" />
  <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml" />
  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml" />
  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml" />
$slideOverrides
</Types>"""
    }

    private fun relsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml" />
</Relationships>"""

    private fun presentationXml(slideCount: Int): String {
        val slideIds = (1..slideCount).joinToString("\n") {
            """    <p:sldId id="${256 + it}" r:id="rId$it" />"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:sldMasterIdLst>
    <p:sldMasterId id="2147483648" r:id="rId2147483648" />
  </p:sldMasterIdLst>
  <p:sldIdLst>
$slideIds
  </p:sldIdLst>
  <p:sldSz cx="9144000" cy="6858000" type="screen4x3" />
  <p:notesSz cx="6858000" cy="9144000" />
</p:presentation>"""
    }

    private fun presentationRels(slideCount: Int): String {
        val slideRels = (1..slideCount).joinToString("\n") {
            """  <Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="ppt/slides/slide$it.xml" />"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$slideRels
  <Relationship Id="rId2147483648" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="ppt/slideMasters/slideMaster1.xml" />
  <Relationship Id="rId2147483649" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="ppt/theme/theme1.xml" />
</Relationships>"""
    }

    private fun themeXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="NoteFlow">
  <a:themeElements>
    <a:clrScheme name="NoteFlow">
      <a:dk1><a:srgbClr val="000000" /></a:dk1>
      <a:lt1><a:srgbClr val="FFFFFF" /></a:lt1>
      <a:dk2><a:srgbClr val="000000" /></a:dk2>
      <a:lt2><a:srgbClr val="FFFFFF" /></a:lt2>
      <a:accent1><a:srgbClr val="4472C4" /></a:accent1>
      <a:accent2><a:srgbClr val="ED7D31" /></a:accent2>
      <a:accent3><a:srgbClr val="A5A5A5" /></a:accent3>
      <a:accent4><a:srgbClr val="FFC000" /></a:accent4>
      <a:accent5><a:srgbClr val="5B9BD5" /></a:accent5>
      <a:accent6><a:srgbClr val="70AD47" /></a:accent6>
      <a:hlink><a:srgbClr val="0563C1" /></a:hlink>
      <a:folHlink><a:srgbClr val="954F72" /></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="NoteFlow">
      <a:majorFont><a:latin typeface="Arial" /></a:majorFont>
      <a:minorFont><a:latin typeface="Arial" /></a:minorFont>
    </a:fontScheme>
    <a:fmtScheme name="Office">
      <a:fillStyleLst><a:solidFill><a:schemeClr val="phClr" /></a:solidFill><a:solidFill><a:schemeClr val="phClr" /></a:solidFill><a:solidFill><a:schemeClr val="phClr" /></a:solidFill></a:fillStyleLst>
      <a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr" /></a:solidFill></a:ln><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr" /></a:solidFill></a:ln><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr" /></a:solidFill></a:ln></a:lnStyleLst>
      <a:effectStyleLst><a:effectStyle><a:effectLst /></a:effectStyle><a:effectStyle><a:effectLst /></a:effectStyle><a:effectStyle><a:effectLst /></a:effectStyle></a:effectStyleLst>
      <a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr" /></a:solidFill><a:solidFill><a:schemeClr val="phClr" /></a:solidFill><a:solidFill><a:schemeClr val="phClr" /></a:solidFill></a:bgFillStyleLst>
    </a:fmtScheme>
  </a:themeElements>
</a:theme>"""

    private fun slideLayoutXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" type="blank" preserve="1">
  <p:cSld name="Blank">
    <p:bg><p:bgRef idx="1001"><a:schemeClr val="phClr" /></p:bgRef></p:bg>
    <p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name="" /><p:cNvGrpSpPr /><p:nvPr /></p:nvGrpSpPr><p:grpSpPr /></p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping /></p:clrMapOvr>
</p:sldLayout>"""

    private fun slideLayoutRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml" />
</Relationships>"""

    private fun slideMasterXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:cSld>
    <p:bg><p:bgRef idx="1001"><a:schemeClr val="phClr" /></p:bgRef></p:bg>
    <p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name="" /><p:cNvGrpSpPr /><p:nvPr /></p:nvGrpSpPr><p:grpSpPr /></p:spTree>
  </p:cSld>
  <p:clrMap>
    <a:dk1><a:sysClr val="windowText" lastClr="000000" /></a:dk1>
    <a:lt1><a:sysClr val="window" lastClr="FFFFFF" /></a:lt1>
    <a:dk2><a:srgbClr val="000000" /></a:dk2>
    <a:lt2><a:srgbClr val="FFFFFF" /></a:lt2>
    <a:accent1><a:srgbClr val="4472C4" /></a:accent1>
    <a:accent2><a:srgbClr val="ED7D31" /></a:accent2>
    <a:accent3><a:srgbClr val="A5A5A5" /></a:accent3>
    <a:accent4><a:srgbClr val="FFC000" /></a:accent4>
    <a:accent5><a:srgbClr val="5B9BD5" /></a:accent5>
    <a:accent6><a:srgbClr val="70AD47" /></a:accent6>
    <a:hlink><a:srgbClr val="0563C1" /></a:hlink>
    <a:folHlink><a:srgbClr val="954F72" /></a:folHlink>
  </p:clrMap>
  <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1" /></p:sldLayoutIdLst>
</p:sldMaster>"""

    private fun slideMasterRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml" />
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml" />
</Relationships>"""

    private fun slideRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml" />
</Relationships>"""

    private fun appXml(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>NoteFlow AI</Application>
  <Title>$title</Title>
  <Company>NoteFlow</Company>
</Properties>"""

    private fun coreXml(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
  xmlns:dc="http://purl.org/dc/elements/1.1/"
  xmlns:dcterms="http://purl.org/dc/terms/"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>$title</dc:title>
  <dc:creator>NoteFlow AI</dc:creator>
</cp:coreProperties>"""

    // --- Slide XML ---

    private fun slideXml(slide: SlideData, slideNum: Int): String {
        val contentElements = buildString {
            var yPos = 1500000L

            // Title
            appendLine("""  <p:sp>
    <p:nvSpPr><p:cNvPr id="2" name="Title" /><p:nvPr><p:ph type="title" /></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="457200" y="$yPos" /><a:ext cx="8229600" cy="685800" /></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr /><a:lstStyle /><a:p><a:pPr algn="l" /><a:r><a:rPr lang="en-US" dirty="0" sz="2800" b="1" /><a:t>${escapeXml(slide.title)}</a:t></a:r><a:endParaRPr lang="en-US" dirty="0" /></a:p></p:txBody>
  </p:sp>""")
            yPos += 900000L

            if (slide.isTitleSlide) {
                // Title slide — just the title, no content
            } else if (slide.content.isNotEmpty()) {
                // Rich mode: render SlideBlocks
                for (block in slide.content) {
                    when (block) {
                        is SlideBlock.Bullet -> {
                            appendLine("""  <p:sp>
    <p:nvSpPr><p:cNvPr id="${slideNum * 100 + 10}" name="Text" /><p:nvPr /></p:nvSpPr><p:spPr><a:xfrm><a:off x="571500" y="$yPos" /><a:ext cx="7874000" cy="342900" /></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr /><a:lstStyle /><a:p><a:pPr algn="l" /><a:r><a:rPr lang="en-US" dirty="0" sz="1800" /><a:t>${escapeXml(block.text)}</a:t></a:r><a:endParaRPr lang="en-US" dirty="0" /></a:p></p:txBody>
  </p:sp>""")
                            yPos += 400000L
                        }
                        is SlideBlock.Code -> {
                            appendLine("""  <p:sp>
    <p:nvSpPr><p:cNvPr id="${slideNum * 100 + 20}" name="Code" /><p:nvPr /></p:nvSpPr><p:spPr><a:xfrm><a:off x="571500" y="$yPos" /><a:ext cx="7874000" cy="500000" /></a:xfrm><a:solidFill><a:srgbClr val="F5F5F5" /></a:solidFill><a:ln><a:solidFill><a:srgbClr val="DDDDDD" /></a:solidFill></a:ln></p:spPr>
    <p:txBody><a:bodyPr /><a:lstStyle /><a:p><a:pPr algn="l" /><a:r><a:rPr lang="en-US" dirty="0" sz="1400" /><a:rFonts a:ascii="Courier New" a:hAnsi="Courier New" /><a:t>${escapeXml(block.code.take(200))}</a:t></a:r><a:endParaRPr lang="en-US" dirty="0" /></a:p></p:txBody>
  </p:sp>""")
                            yPos += 550000L
                        }
                        is SlideBlock.Table -> {
                            val headerLine = block.headers.joinToString(" | ")
                            appendLine("""  <p:sp>
    <p:nvSpPr><p:cNvPr id="${slideNum * 100 + 30}" name="TableHeader" /><p:nvPr /></p:nvSpPr><p:spPr><a:xfrm><a:off x="571500" y="$yPos" /><a:ext cx="7874000" cy="342900" /></a:xfrm><a:solidFill><a:srgbClr val="E8E8E8" /></a:solidFill></p:spPr>
    <p:txBody><a:bodyPr /><a:lstStyle /><a:p><a:pPr algn="l" /><a:r><a:rPr lang="en-US" dirty="0" sz="1600" b="1" /><a:t>${escapeXml(headerLine)}</a:t></a:r><a:endParaRPr lang="en-US" dirty="0" /></a:p></p:txBody>
  </p:sp>""")
                            yPos += 380000L

                            for (row in block.rows) {
                                val rowLine = row.joinToString(" | ")
                                appendLine("""  <p:sp>
    <p:nvSpPr><p:cNvPr id="${slideNum * 100 + 40}" name="TableRow" /><p:nvPr /></p:nvSpPr><p:spPr><a:xfrm><a:off x="571500" y="$yPos" /><a:ext cx="7874000" cy="342900" /></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr /><a:lstStyle /><a:p><a:pPr algn="l" /><a:r><a:rPr lang="en-US" dirty="0" sz="1400" /><a:t>${escapeXml(rowLine)}</a:t></a:r><a:endParaRPr lang="en-US" dirty="0" /></a:p></p:txBody>
  </p:sp>""")
                                yPos += 350000L
                            }
                        }
                    }
                }
            } else {
                // Simple mode: render bullets
                for (bullet in slide.bullets) {
                    appendLine("""  <p:sp>
    <p:nvSpPr><p:cNvPr id="${slideNum * 100 + 10}" name="Text" /><p:nvPr /></p:nvSpPr><p:spPr><a:xfrm><a:off x="571500" y="$yPos" /><a:ext cx="7874000" cy="342900" /></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr /><a:lstStyle /><a:p><a:pPr algn="l" /><a:r><a:rPr lang="en-US" dirty="0" sz="1800" /><a:t>${escapeXml(bullet)}</a:t></a:r><a:endParaRPr lang="en-US" dirty="0" /></a:p></p:txBody>
  </p:sp>""")
                    yPos += 400000L
                }
            }
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:cSld>
    <p:bg><p:bgRef idx="1001"><a:schemeClr val="phClr" /></p:bgRef></p:bg>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name="" /><p:cNvGrpSpPr /><p:nvPr /></p:nvGrpSpPr>
      <p:grpSpPr />
$contentElements
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping /></p:clrMapOvr>
  <p:sldLayoutIdLst>
    <p:sldLayoutId id="2147483649" r:id="rId1" />
  </p:sldLayoutIdLst>
</p:sld>"""
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
