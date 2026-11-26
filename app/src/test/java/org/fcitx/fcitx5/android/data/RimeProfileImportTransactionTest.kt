/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RimeProfileImportTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        file.writeBytes(bytes)
    }

    private fun fixture(timestamp: Long = 100): Pair<RimeProfileImportTransaction, File> {
        val imports = temporary.newFolder("imports-$timestamp")
        val parent = temporary.newFolder("profiles-$timestamp")
        val archive = File(imports, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []\n", "custom.dict.yaml" to "entries")
        return RimeProfileImportTransaction.prepare(
            archive.absolutePath,
            imports,
            parent,
            emptySet(),
            timestamp,
        ) to parent
    }

    @Test fun prepareConfinesArchivesAndChoosesAnUnusedProfileName() {
        val imports = temporary.newFolder("imports")
        val parent = temporary.newFolder("profiles")
        val archive = File(imports, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []\n")
        File(parent, "import-10").mkdir()
        File(parent, ".import-11.importing").mkdir()

        val transaction = RimeProfileImportTransaction.prepare(
            archive.absolutePath,
            imports,
            parent,
            setOf("import-12"),
            timestamp = 10,
        )

        assertEquals("import-13", transaction.name)
        assertEquals(File(parent, "import-13").canonicalFile, transaction.target.canonicalFile)
        val outside = File(temporary.root, "outside.zip").apply { writeBytes(archive.readBytes()) }
        assertThrows(IllegalArgumentException::class.java) {
            RimeProfileImportTransaction.prepare(outside.absolutePath, imports, parent, emptySet(), 20)
        }
    }

    @Test fun bundledRestoreUsesSeparateNamedDirectory() {
        val imports = temporary.newFolder("bundled-imports")
        val parent = temporary.newFolder("bundled-profiles")
        val archive = File(imports, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []")
        File(parent, "wanxiang").mkdir()
        File(parent, "wanxiang/personal.txt").writeText("keep")
        val transaction = RimeProfileImportTransaction.prepare(
            archive.absolutePath, imports, parent, setOf("wanxiang"),
            timestamp = 100, preferredName = "wanxiang",
        )
        assertTrue(transaction.name.startsWith("wanxiang-"))
        transaction.install()
        assertEquals("keep", File(parent, "wanxiang/personal.txt").readText())
    }

    @Test fun exactNameIsUsedWithoutSuffixAndCollisionsAreRejected() {
        val imports = temporary.newFolder("named-imports")
        val parent = temporary.newFolder("named-profiles")
        val archive = File(imports, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []")
        fun prepare(name: String, registered: Set<String> = emptySet()) =
            RimeProfileImportTransaction.prepare(
                archive.absolutePath, imports, parent, registered, exactName = name,
            )
        for (name in listOf("", "../outside", "a/b", "/absolute", ".hidden", " padded ")) {
            assertThrows(IllegalArgumentException::class.java) { prepare(name) }
        }
        assertThrows(IllegalArgumentException::class.java) { prepare("existing", setOf("existing")) }
        val transaction = prepare("万象新版")
        assertEquals("万象新版", transaction.name)
        assertEquals(File(parent, "万象新版"), transaction.install())
        assertThrows(IllegalArgumentException::class.java) { prepare("万象新版") }
        File(parent, ".busy.importing").mkdir()
        assertThrows(IllegalArgumentException::class.java) { prepare("busy") }
    }

    @Test fun bundledOverwriteStagesOfficialDataButNotUserState() {
        val parent = temporary.newFolder("bundled-overwrite")
        val archive = File(parent, "profile.zip")
        zip(archive, "default.yaml" to "new", "wanxiang.schema.yaml" to "schema",
            "data/example.txt" to "data", "lua/data/charset.reverse.bin" to "binary",
            "user.yaml" to "state", "zc.userdb/data" to "db",
            "default.custom.yaml" to "override", "custom_phrase.dict.yaml" to "personal")
        val user = File(parent, "user").apply { mkdir() }
        File(user, "default.custom.yaml").writeText("keep")
        val staging = File(parent, "stage")
        val plan = BundledWanxiang.prepareUpdate(archive, staging, user)
        assertEquals(user, plan.user)
        assertEquals(setOf("default.yaml", "wanxiang.schema.yaml", "data/example.txt",
            "lua/data/charset.reverse.bin"), staging.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(staging).invariantSeparatorsPath }.toSet())
        assertEquals("keep", File(user, "default.custom.yaml").readText())
    }

    @Test fun initialInstallNeverOverwritesAnExistingDirectory() {
        val parent = temporary.newFolder("first-install")
        val archive = File(parent, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []")
        val target = File(parent, "rime")
        BundledWanxiang.installInitialArchive(archive, target)
        File(target, "personal.txt").writeText("keep")
        assertThrows(IllegalStateException::class.java) {
            BundledWanxiang.installInitialArchive(archive, target)
        }
        assertEquals("keep", File(target, "personal.txt").readText())
    }

    @Test fun installPublishesOnlyTheCompleteExtractedDirectory() {
        val (transaction, parent) = fixture()

        val target = transaction.install()

        assertEquals("schema_list: []\n", File(target, "default.yaml").readText())
        assertEquals("entries", File(target, "custom.dict.yaml").readText())
        assertFalse(File(parent, ".import-100.importing").exists())
    }

    @Test fun invalidArchiveLeavesNeitherStagingNorTarget() {
        val imports = temporary.newFolder("unsafe-imports")
        val parent = temporary.newFolder("unsafe-profiles")
        val archive = File(imports, "profile.zip")
        zip(archive, "../escape" to "bad")
        val transaction = RimeProfileImportTransaction.prepare(
            archive.absolutePath, imports, parent, emptySet(), timestamp = 200
        )

        assertThrows(IllegalArgumentException::class.java) { transaction.install() }

        assertFalse(File(parent, ".import-200.importing").exists())
        assertFalse(File(parent, "import-200").exists())
    }

    @Test fun failureCleanupRemovesOnlyAnUnregisteredInstall() {
        val (unregistered, _) = fixture(300)
        val unregisteredTarget = unregistered.install()
        unregistered.recoverAfterFailure()
        assertFalse(unregisteredTarget.exists())

        val (registered, _) = fixture(400)
        val registeredTarget = registered.install()
        registered.markRegistered()
        registered.recoverAfterFailure()
        assertTrue(registeredTarget.isDirectory)
        assertTrue(File(registeredTarget, "default.yaml").isFile)
    }
}
