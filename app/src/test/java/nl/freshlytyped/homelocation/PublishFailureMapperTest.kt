package nl.freshlytyped.homelocation

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishFailureMapperTest {

    private val mapper = PublishFailureMapper

    @Test
    fun `status codes map to the right categories`() {
        assertEquals(PublishFailureCategory.TIMEOUT, mapper.categoryFor(408))
        assertEquals(PublishFailureCategory.CONFIGURATION, mapper.categoryFor(401))
        assertEquals(PublishFailureCategory.CONFIGURATION, mapper.categoryFor(403))
        assertEquals(PublishFailureCategory.CONFIGURATION, mapper.categoryFor(400))
        assertEquals(PublishFailureCategory.SERVER, mapper.categoryFor(500))
        assertEquals(PublishFailureCategory.SERVER, mapper.categoryFor(503))
    }

    @Test
    fun `exceptions map to the right categories`() {
        assertEquals(PublishFailureCategory.TIMEOUT, mapper.categoryFor(SocketTimeoutException()))
        assertEquals(PublishFailureCategory.OFFLINE, mapper.categoryFor(ConnectException()))
        assertEquals(PublishFailureCategory.OFFLINE, mapper.categoryFor(UnknownHostException()))
        assertEquals(PublishFailureCategory.OFFLINE, mapper.categoryFor(NoRouteToHostException()))
        assertEquals(PublishFailureCategory.SERVER, mapper.categoryFor(RuntimeException("boom")))
        assertEquals(
            PublishFailureCategory.TIMEOUT,
            mapper.categoryFor(PublishException(PublishFailureCategory.TIMEOUT))
        )
    }

    @Test
    fun `only offline and timeout are transient`() {
        assertTrue(mapper.isTransient(PublishFailureCategory.OFFLINE))
        assertTrue(mapper.isTransient(PublishFailureCategory.TIMEOUT))
        assertFalse(mapper.isTransient(PublishFailureCategory.CONFIGURATION))
        assertFalse(mapper.isTransient(PublishFailureCategory.SERVER))
    }
}
