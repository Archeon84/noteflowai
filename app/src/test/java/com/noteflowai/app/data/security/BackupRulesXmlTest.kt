package com.noteflowai.app.data.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRulesXmlTest {

    @Test
    fun `backup_rules xml excludes sharedpref, database, file, and root`() {
        val file = File("src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", file.exists())
        val content = file.readText()
        assertTrue(content.contains("""<exclude domain="sharedpref" path="." />"""))
        assertTrue(content.contains("""<exclude domain="database" path="." />"""))
        assertTrue(content.contains("""<exclude domain="file" path="." />"""))
        assertTrue(content.contains("""<exclude domain="root" path="." />"""))
    }

    @Test
    fun `data_extraction_rules xml excludes sharedpref, database, file, and root for cloud and device-transfer`() {
        val file = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", file.exists())
        val content = file.readText()
        assertTrue(content.contains("<cloud-backup>"))
        assertTrue(content.contains("<device-transfer>"))
        assertTrue(content.contains("""<exclude domain="sharedpref" path="." />"""))
        assertTrue(content.contains("""<exclude domain="database" path="." />"""))
        assertTrue(content.contains("""<exclude domain="file" path="." />"""))
        assertTrue(content.contains("""<exclude domain="root" path="." />"""))
    }
}
