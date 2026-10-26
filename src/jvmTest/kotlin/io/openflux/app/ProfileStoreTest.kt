package io.openflux.app

import io.openflux.desktop.data.FileProfileRepository
import io.openflux.desktop.model.TransportType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileStoreTest {
    @Test
    fun aProfileWithARemovedTransportStillLoadsTheOthers() {
        val dir = Files.createTempDirectory("ofprofiles").toFile()
        File(dir, "profiles.json").writeText(
            """
            [
              {"id": "gone", "name": "Old UDP", "transport": "FLUXFAST", "value": "203.0.113.10:8446",
               "secret": "k", "extras": [{"type": "FLUXFAST", "value": "203.0.113.11:8446", "priority": 110}]},
              {"id": "ok", "name": "Yandex", "transport": "VYANDEX", "value": "https://docs.yandex.ru/edit/d/x"}
            ]
            """.trimIndent(),
        )
        val profiles = FileProfileRepository(dir).profiles.value
        assertEquals(listOf("gone", "ok"), profiles.map { it.id })
        assertEquals(TransportType.VYANDEX, profiles[0].transport)
        assertEquals(TransportType.VYANDEX, profiles[0].extras.single().type)
        assertEquals(TransportType.VYANDEX, profiles[1].transport)
        dir.deleteRecursively()
    }
}
