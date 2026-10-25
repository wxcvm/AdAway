package org.adaway.ui.compose

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QueryLogCsvExportTest {
    private fun directory(): File = Files.createTempDirectory("adaway-csv-test-").toFile()
    private fun cleanup(dir: File) { dir.listFiles()?.forEach { check(it.delete()) };check(dir.delete()) }
    @Test fun publishesOnlyACompleteCsvAndKeepsObservationIdentity()=runBlocking {
        val dir=directory()
        try {
            var shared:File?=null
            withQueryLogCsvExport(dir,listOf(QueryLogEntry(host="same.example",action=2,
                generation="a".repeat(32),sequence="2"))) { file ->
                shared=file
                assertTrue(file.name.endsWith(".csv"));assertFalse(file.name.endsWith(".part"))
                val lines=file.readLines();assertEquals(2,lines.size)
                assertTrue(lines[1].contains(",local_handled,"));assertTrue(lines[1].endsWith(","+"a".repeat(32)+",2"))
                assertEquals(1,dir.listFiles()!!.size)
            }
            assertTrue(shared!!.isFile)
        } finally {cleanup(dir)}
    }
    @Test fun publicationOrShareFailureLeavesNoPartialOrCompletedExport()=runBlocking {
        val dir=directory()
        try {
            try {withQueryLogCsvExport(dir,listOf(QueryLogEntry(host="example.test"))){throw IOException("share failure")};fail("expected failure")}
            catch(expected:IOException){assertEquals("share failure",expected.message)}
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {cleanup(dir)}
    }
    @Test fun cancellationAfterRenameBeforeSharingDeletesTheCompletedFile()=runBlocking {
        val dir=directory();val prepared=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        try {
            val job=launch {withQueryLogCsvExport(dir,List(300){QueryLogEntry(host="example.test")}){
                prepared.complete(Unit);gate.await()
            }}
            prepared.await();assertEquals(1,dir.listFiles()!!.size)
            job.cancelAndJoin();assertTrue(dir.listFiles()!!.isEmpty())
        } finally {gate.cancel();cleanup(dir)}
    }
    @Test fun anAlreadyCancelledExportCreatesNoShareableFile()=runBlocking {
        val dir=directory();var shared=false
        try {
            val job=launch(start=CoroutineStart.LAZY){withQueryLogCsvExport(dir,List(10000){QueryLogEntry(host="example.test")}){shared=true}}
            job.cancel();job.start();job.join()
            assertFalse(shared);assertTrue(dir.listFiles()!!.isEmpty())
        } finally {cleanup(dir)}
    }
}
