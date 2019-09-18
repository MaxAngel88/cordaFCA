package com.example.state

import com.example.contract.MessageContract
import com.example.schema.MessageSchemaV1
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.time.Instant

@BelongsToContract(MessageContract::class)
data class MessageState(val mittente: Party,
                        val destinatario: Party,
                        val regolatore: Party,
                        val time: Instant,
                        val msg: String,
                        val sicurezza: Int,
                        override val linearId: UniqueIdentifier = UniqueIdentifier()):
        LinearState, QueryableState {
    /** The public keys of the involved parties. */
    override val participants: List<AbstractParty> get() = listOf(mittente, destinatario, regolatore)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is MessageSchemaV1 -> MessageSchemaV1.PersistentMessage(
                    this.mittente.name.toString(),
                    this.destinatario.name.toString(),
                    this.regolatore.name.toString(),
                    this.time,
                    this.msg,
                    this.sicurezza,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MessageSchemaV1)
}
