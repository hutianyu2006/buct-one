package com.buct.one.data.source.electricity

import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.store.ElectricitySnapshotStore
import com.buct.one.platform.InMemoryKeyValueStore
import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElectricityControllerTest {

    private val target = ElectricityRoomTarget(
        schoolid = "1503634598",
        campusName = "昌平新校区",
        buildingid = "CCPS.BUCT.B0601",
        buildingName = "樱花苑1号楼",
        floorid = "CCPS.BUCT.B0601.F01",
        floorName = "一层",
        roomid = "CCPS.BUCT.B0601.F01.R0102",
        roomName = "102",
    )

    private fun controller(
        body: String = """{"balance":"12.34","unit":"元","status":"正常"}""",
        status: HttpStatusCode = HttpStatusCode.OK,
        extraHeaders: List<Pair<String, List<String>>> = emptyList(),
        cache: ElectricitySnapshotStore? = null,
        catalog: ElectricityRoomCatalogSource = object : ElectricityRoomCatalogSource {
            override fun readCatalogJson(): String = """{"factorycode":"E005","crawledAt":"","campuses":[]}"""
        },
    ): ElectricityController {
        val engine = MockEngine {
            respond(
                body,
                status,
                headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json")) + extraHeaders).toTypedArray()),
            )
        }
        return ElectricityController(
            gateway = ElectricityGatewayClient(buildHttpClient(engine), userAgent = "test-UA"),
            catalogSource = catalog,
            cache = cache,
        )
    }

    @Test
    fun refresh_successStoresReading() = runTest {
        val controller = controller()
        controller.refresh(listOf(target), clientId = "cid", studentId = "20230001", signSecret = "s")
        val state = controller.state.value
        assertTrue(state is ElectricityUiState.Loaded, "state=$state")
        assertEquals(1, (state as ElectricityUiState.Loaded).readings.size)
    }

    @Test
    fun refresh_failureKeepsPreviousReadings() = runTest {
        val ok = controller()
        ok.refresh(listOf(target), "cid", "20230001", "s")
        assertTrue(ok.lastReadings() != null)

        val failing = controller(status = HttpStatusCode.InternalServerError, body = """{"message":"boom"}""")
        failing.refresh(listOf(target), "cid", "20230001", "s")
        assertTrue(failing.state.value is ElectricityUiState.Failed)
    }

    @Test
    fun bannedShortCircuits() = runTest {
        val controller = controller(
            status = HttpStatusCode.Forbidden,
            body = """{"banned":true,"message":"已被封禁"}""",
        )
        controller.refresh(listOf(target), "cid", "20230001", "s")
        val state = controller.state.value
        assertTrue(state is ElectricityUiState.Banned, "state=$state")
    }

    @Test
    fun catalogUnavailableIsExplicit() = runTest {
        val controller = controller(
            catalog = object : ElectricityRoomCatalogSource {
                override fun readCatalogJson(): String? = null
            },
        )
        controller.loadCatalog()
        assertEquals(ElectricityUiState.CatalogUnavailable, controller.state.value)
    }

    @Test
    fun cacheRestoreMarksStale() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = ElectricitySnapshotStore(kv)
        val first = controller(cache = store)
        first.refresh(listOf(target), "cid", "20230001", "s")
        assertTrue(kv.getString(ElectricitySnapshotStore.DEFAULT_KEY) != null)

        val second = controller(cache = ElectricitySnapshotStore(kv))
        assertTrue(second.restoreFromCache())
        val state = second.state.value
        assertTrue(state is ElectricityUiState.Loaded)
        assertEquals(1, (state as ElectricityUiState.Loaded).readings.size)
        assertEquals(listOf(target), second.targets())
    }
}
