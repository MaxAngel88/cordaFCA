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
object BlacklistSchema

/**
 * An MessageState schema.
 */
object BlacklistSchemaV1 : MappedSchema(
        schemaFamily = BlacklistSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentBlacklist::class.java)) {

    @Entity
    @Table(name = "blacklist_states")
    class PersistentBlacklist(
            @Column(name = "regolatore")
            var regolatoreName: String,

            @Column(name = "memberA")
            var memberAName: String,

            @Column(name = "memberB")
            var memberBName: String,

            @Column(name = "memberC")
            var memberCName: String,

            @Column(name = "m1_m2")
            var m1_m2: String,

            @Column(name = "m1_m3")
            var m1_m3: String,

            @Column(name = "m2_m3")
            var m2_m3: String,

            @Column(name = "m2_m1")
            var m2_m1: String,

            @Column(name = "m3_m1")
            var m3_m1: String,

            @Column(name = "m3_m2")
            var m3_m2: String,

            @Column(name = "info")
            var info: String,

            @Column(name = "time")
            var time: Instant,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(regolatoreName = "", memberAName = "", memberBName = "", memberCName = "", m1_m2 = "", m1_m3 = "", m2_m3 = "", m2_m1 = "", m3_m1 = "", m3_m2 = "", info = "", time = Instant.now(), linearId = UUID.randomUUID())
    }
}