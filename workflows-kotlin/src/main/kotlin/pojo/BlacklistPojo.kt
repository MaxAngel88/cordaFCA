package pojo

import net.corda.core.serialization.CordaSerializable
import java.util.*

@CordaSerializable
data class BlacklistPojo(
        val m1_m2: Boolean = true,
        val m1_m3: Boolean = true,
        val m2_m3: Boolean = true,
        val m2_m1: Boolean = true,
        val m3_m1: Boolean = true,
        val m3_m2: Boolean = true,
        val info: String = ""
)