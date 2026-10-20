package dev.lumen.core.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumen.core.model.Action
import dev.lumen.core.model.ActionPreview
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRecord
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Plan
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.Step
import dev.lumen.core.model.StepId
import dev.lumen.core.model.StepState
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store's device tests.
 *
 * The database is encrypted, so these run on a device rather than on the
 * JVM. The migration harness is here too: a schema change without a tested
 * migration is a data-loss bug waiting for a release.
 */
@RunWith(AndroidJUnit4::class)
class RoomStateStoreTest {

    private lateinit var context: Context
    private lateinit var database: LumenDatabase
    private lateinit var store: RoomStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomStateStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aPlanSurvivesARoundTrip() = runTest {
        val plan = samplePlan()

        store.savePlan(plan)
        val loaded = store.loadPlan(plan.runId)

        assertNotNull(loaded)
        assertEquals(plan.version, loaded?.version)
        assertEquals(plan.steps.size, loaded?.steps?.size)
        assertEquals(plan.steps.first().action, loaded?.steps?.first()?.action)
        assertEquals(plan.steps.first().reason.text, loaded?.steps?.first()?.reason?.text)
    }

    @Test
    fun aStepUpdateReplacesTheRow() = runTest {
        val plan = samplePlan()
        store.savePlan(plan)

        val updated = plan.steps.first().copy(state = StepState.COMPLETE)
        store.saveStep(plan.runId, updated)

        assertEquals(StepState.COMPLETE, store.loadStep(updated.id)?.state)
    }

    @Test
    fun observationsAreAppendedInOrder() = runTest {
        val plan = samplePlan()
        val stepId = plan.steps.first().id

        store.appendObservation(plan.runId, stepId, observation("first"))
        store.appendObservation(plan.runId, stepId, observation("second"))

        val loaded = store.loadObservations(plan.runId)
        assertEquals(2, loaded.size)
        assertEquals("first", loaded[0].app)
        assertEquals("second", loaded[1].app)
    }

    @Test
    fun anApprovalIsStagedAndResolved() = runTest {
        val plan = samplePlan()
        val approval = ApprovalRecord(
            id = ApprovalId("01JBX0000000000000000000000"),
            runId = plan.runId,
            stepId = plan.steps.first().id,
            riskClass = RiskClass.PAYMENT,
            title = "Pay EUR 382",
            detail = "Iberia, one passenger",
            app = "com.example.travel",
            appLabel = "Travel",
            consequences = listOf("The money leaves the account"),
            actionPreview = ActionPreview(kind = "TAP", label = "Pay"),
            state = ApprovalState.PENDING,
            createdAtMs = 1_000L,
            leaseExpiresAtMs = 61_000L,
        )

        store.saveApproval(approval)
        assertEquals(1, store.loadPendingApprovals(plan.runId).size)

        store.saveApproval(approval.copy(state = ApprovalState.APPROVED, resolvedAtMs = 2_000L))
        assertEquals(0, store.loadPendingApprovals(plan.runId).size)
        assertEquals(ApprovalState.APPROVED, store.loadApproval(approval.id)?.state)
    }

    @Test
    fun aSettingRoundTrips() = runTest {
        assertNull(store.loadSetting("indicator_mode"))
        store.saveSetting("indicator_mode", "EDGE_GLOW")
        assertEquals("EDGE_GLOW", store.loadSetting("indicator_mode"))
    }

    @Test
    fun theDatabaseFileDoesNotContainPlaintext() = runTest {
        val keyProvider = DatabaseKeyProvider(context)
        val file = context.getDatabasePath("encryption-probe.db")
        file.parentFile?.mkdirs()
        file.delete()

        val probe = Room.databaseBuilder(context, LumenDatabase::class.java, "encryption-probe.db")
            .openHelperFactory(sqlCipherFactory(keyProvider.passphrase()))
            .allowMainThreadQueries()
            .build()

        val marker = "marker-of-plaintext-that-must-not-appear"
        val plan = samplePlan().copy(summary = marker)
        RoomStateStore(probe).savePlan(plan)
        probe.close()

        val bytes = file.readBytes()
        val content = String(bytes, Charsets.ISO_8859_1)

        assertFalse("the marker must not be readable in the database file", content.contains(marker))
        file.delete()
    }
}

/**
 * The migration harness.
 *
 * Version 1 is the first schema, so there is nothing to migrate from yet.
 * The harness exists now, before the second version exists, because a
 * migration test written after the first schema change is a migration test
 * that is never written.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun theExportedSchemaIsCommitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertNotNull(instrumentation)

        val schemas = File("schemas").listFiles().orEmpty()
        assertTrue(
            "the exported schema must be committed under the schemas directory",
            schemas.any { it.name.contains("1.json") },
        )
    }

    private companion object {
        fun assertTrue(message: String, condition: Boolean) =
            org.junit.Assert.assertTrue(message, condition)
    }
}
