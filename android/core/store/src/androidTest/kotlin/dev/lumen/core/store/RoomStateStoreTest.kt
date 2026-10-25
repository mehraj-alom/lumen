package dev.lumen.core.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.model.Action
import dev.lumen.core.model.ActionPreview
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRecord
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Assertion
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Plan
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RelativeRect
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.Step
import dev.lumen.core.model.StepId
import dev.lumen.core.model.StepState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            id = ApprovalId("01JBX000000000000000000000"),
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
 * that is never written. It checks the schema the device created, because
 * the exported JSON is a build artefact that is not on the device.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun theVersionOneSchemaCreatesEveryTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, LumenDatabase::class.java, "migration-probe.db")
            .allowMainThreadQueries()
            .build()

        val tables = try {
            val db = database.openHelper.writableDatabase
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        } finally {
            database.close()
            context.deleteDatabase("migration-probe.db")
        }

        listOf("runs", "plans", "steps", "observations", "approvals", "budget_ledger", "settings")
            .forEach { table ->
                assertTrue("the schema does not contain the '$table' table", tables.contains(table))
            }
    }
}

/** A plan with one step, for the round trips above. */
private fun samplePlan(): Plan = Plan(
    runId = RunId("01JBX000000000000000000001"),
    version = 1,
    steps = listOf(
        Step(
            id = StepId("01JBX000000000000000000002"),
            planVersion = 1,
            phase = "research",
            intent = "Open the results list",
            reason = Reason.planner("the prices are only on the results page"),
            action = Action.ClickText("Prices"),
            expectation = Assertion.TextPresent("Total price"),
            riskClass = RiskClass.NONE,
        ),
    ),
    createdAtMs = 1_000L,
    summary = "the scripted plan",
)

/** A one-node observation whose foreground app is [app]. */
private fun observation(app: String): Observation = Observation(
    app = app,
    nodes = listOf(
        NodeRef(
            id = 0,
            className = "android.widget.Button",
            text = "Continue",
            packageName = app,
            bounds = RelativeRect(0.0, 0.0, 1.0, 0.1),
            clickable = true,
        ),
    ),
)
