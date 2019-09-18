package com.example.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for MessageState.
 */
object MessageSchema

/**
 * An MessageState schema.
 */
object MessageSchemaV1 : MappedSchema(
        schemaFamily = MessageSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentMessage::class.java)) {

    @Entity
    @Table(name = "massage_states")
    class PersistentMessage(
            @Column(name = "mittente")
            var mittenteName: String,

            @Column(name = "destinatario")
            var destinatarioName: String,

            @Column(name = "regolatore")
            var regolatoreName: String,

            @Column(name = "time")
            var time: Instant,

            @Column(name = "msg")
            var msg: String,

            @Column(name = "sicurezza")
            var sicurezza: Int,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(mittenteName = "", destinatarioName = "", regolatoreName = "", time = Instant.now(), msg = "", sicurezza =  0, linearId = UUID.randomUUID())
    }
}