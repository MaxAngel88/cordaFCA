package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BlacklistContract
import com.example.state.BlacklistState

import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import pojo.BlacklistPojo

import java.time.Instant
import java.util.*


object BlacklistFlow {

    /**
     *
     * Create Blacklist Flow ------------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class StarterBlacklist(val memberA: Party,
                           val memberB: Party,
                           val memberC: Party,
                           val property: BlacklistPojo) : FlowLogic<BlacklistState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new Message.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the destinatario's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): BlacklistState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val blacklistState = BlacklistState(
                    serviceHub.myInfo.legalIdentities.first(),
                    memberA,
                    memberB,
                    memberC,
                    property.m1_m2,
                    property.m1_m3,
                    property.m2_m3,
                    property.m2_m1,
                    property.m3_m1,
                    property.m3_m2,
                    property.info,
                    Instant.now(),
                    UniqueIdentifier(id = UUID.randomUUID()))

            val txCommand = Command(BlacklistContract.Commands.Create(), blacklistState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(blacklistState, BlacklistContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val memberASession = initiateFlow(memberA)
            val memberBSession = initiateFlow(memberB)
            val memberCSession = initiateFlow(memberC)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(memberASession, memberBSession, memberCSession), GATHERING_SIGS.childProgressTracker()))


            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx,setOf(memberASession, memberBSession, memberCSession), FINALISING_TRANSACTION.childProgressTracker()))
            return blacklistState
        }
    }

    @InitiatedBy(StarterBlacklist::class)
    class ReceiverBlacklist(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an message transaction." using (output is BlacklistState)
                    val message = output as BlacklistState
                    /* "other rule message" using (output is regola_nuova) */
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }

    /***
     *
     * Blacklist Update Flow -----------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class IssuerBlacklist(val blacklistLinearId: String,
                          val property: BlacklistPojo) : FlowLogic<BlacklistState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on transfert Message.")
            object VERIFYIGN_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the destinatario's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYIGN_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): BlacklistState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val myLegalIdentity = serviceHub.myInfo.legalIdentities.first()
            if (myLegalIdentity.name.organisation != "FCA") {
                throw FlowException("node " + serviceHub.myInfo.legalIdentities.first() + " cannot start the issue flow")
            }

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            var criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            var customCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(blacklistLinearId)))
            criteria = criteria.and(customCriteria)

            val oldBlacklistStateList = serviceHub.vaultService.queryBy<BlacklistState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldBlacklistStateList.size > 1 || oldBlacklistStateList.isEmpty()) throw FlowException("No blacklist state with UUID: " + UUID.fromString(blacklistLinearId) + " found.")

            val oldBlaklistStateRef = oldBlacklistStateList[0]
            val oldBlacklistState = oldBlaklistStateRef.state.data

            val newBlaklistState = BlacklistState(
                    serviceHub.myInfo.legalIdentities.first(),
                    oldBlacklistState.memberA,
                    oldBlacklistState.memberB,
                    oldBlacklistState.memberC,
                    property.m1_m2,
                    property.m1_m3,
                    property.m2_m3,
                    property.m2_m1,
                    property.m3_m1,
                    property.m3_m2,
                    property.info,
                    Instant.now(),
                    UniqueIdentifier(id = UUID.randomUUID())
            )

            val txCommand = Command(BlacklistContract.Commands.Issue(), newBlaklistState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldBlaklistStateRef)
                    .addOutputState(newBlaklistState, BlacklistContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYIGN_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val memberASession = initiateFlow(oldBlacklistState.memberA)
            val memberBSession = initiateFlow(oldBlacklistState.memberB)
            val memberCSession = initiateFlow(oldBlacklistState.memberC)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(memberASession, memberBSession, memberCSession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(memberASession, memberBSession, memberCSession), FINALISING_TRANSACTION.childProgressTracker()))
            return newBlaklistState
        }

        @InitiatedBy(IssuerBlacklist::class)
        class IssuerAcceptorBlacklist(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val output = stx.tx.outputs.single().data
                        "This must be an message transaction." using (output is BlacklistState)
                        val message = output as BlacklistState
                        /* "other rule message" using (output is regola_nuova) */
                    }
                }
                val txId = subFlow(signTransactionFlow).id

                return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))

            }
        }
    }
}
