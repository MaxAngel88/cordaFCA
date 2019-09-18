package com.example.state

import com.example.contract.BlacklistContract
import com.example.schema.BlacklistSchemaV1
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.time.Instant

@BelongsToContract(BlacklistContract::class)
data class BlacklistState(val regolatore: Party,
                          val memberA: Party,
                          val memberB: Party,
                          val memberC: Party,
                          val m1_m2: Boolean,
                          val m1_m3: Boolean,
                          val m2_m3: Boolean,
                          val m2_m1: Boolean,
                          val m3_m1: Boolean,
                          val m3_m2: Boolean,
                          val info: String,
                          val time: Instant,
                          override val linearId: UniqueIdentifier = UniqueIdentifier()):
        LinearState, QueryableState {
    /** The public keys of the involved parties. */
    override val participants: List<AbstractParty> get() = listOf(regolatore, memberA, memberB, memberC)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is BlacklistSchemaV1 -> BlacklistSchemaV1.PersistentBlacklist(
                    this.regolatore.name.toString(),
                    this.memberA.name.toString(),
                    this.memberB.name.toString(),
                    this.memberC.name.toString(),
                    this.m1_m2.toString(),
                    this.m1_m3.toString(),
                    this.m2_m3.toString(),
                    this.m2_m1.toString(),
                    this.m3_m1.toString(),
                    this.m3_m2.toString(),
                    this.info,
                    this.time,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(BlacklistSchemaV1)
}
