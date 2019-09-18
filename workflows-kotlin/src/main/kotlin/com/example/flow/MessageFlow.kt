package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.MessageContract
import com.example.state.BlacklistState
import com.example.state.MessageState

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
import java.time.Instant
import java.util.*

object MessageFlow {

    /**
     *
     * Create Message Flow ------------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class Starter(val destinatario: Party,
                  val regolatore: Party,
                  val msg: String,
                  val sicurezza: Int) : FlowLogic<MessageState>() {
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
        override fun call(): MessageState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION

            // Control BlacklistRule.
            var criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val lastBlacklistStates = serviceHub.vaultService.queryBy<BlacklistState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (lastBlacklistStates.isEmpty()) throw FlowException("No Blacklist state in the ledger. FCA must create first the Blacklist configuration.")

            val blacklistStateRef = lastBlacklistStates[0]
            val blacklistState = blacklistStateRef.state.data

            if(!RespectRule(serviceHub.myInfo.legalIdentities.first(), destinatario, blacklistState)) throw FlowException("FCA's Blacklist cannot permit this operation.")

            // Generate an unsigned transaction.
            val messageState = MessageState(
                    serviceHub.myInfo.legalIdentities.first(),
                    destinatario,
                    regolatore,
                    Instant.now(),
                    msg,
                    sicurezza,
                    UniqueIdentifier(id = UUID.randomUUID()))

            val txCommand = Command(MessageContract.Commands.Create(), messageState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(messageState, MessageContract.ID)
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
            val destinatarioSession = initiateFlow(destinatario)
            val regolatoreSession = initiateFlow(regolatore)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(destinatarioSession, regolatoreSession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(destinatarioSession, regolatoreSession), FINALISING_TRANSACTION.childProgressTracker()))
            return messageState
        }
    }

    @InitiatedBy(Starter::class)
    class Receiver(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an message transaction." using (output is MessageState)
                    val message = output as MessageState
                    /* "other rule message" using (output is regola_nuova) */
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }

    /***
     *
     * Message Delete Flow -----------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class Deleter(val messageLinearId: String) : FlowLogic<MessageState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on delete Message.")
            object VERIFYIGN_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the otherparty's signature.") {
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
        override fun call(): MessageState {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val myLegalIdentity = serviceHub.myInfo.legalIdentities.first()
            if (myLegalIdentity.name.organisation == "FCA") {
                throw FlowException("node " + serviceHub.myInfo.legalIdentities.first() + " cannot start the issue flow")
            }

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            var criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            var customCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(messageLinearId)))
            criteria = criteria.and(customCriteria)

            val oldMessageStateList = serviceHub.vaultService.queryBy<MessageState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldMessageStateList.size > 1 || oldMessageStateList.isEmpty()) throw FlowException("No message state with UUID: " + UUID.fromString(messageLinearId) + " found.")

            val oldMessageStateRef = oldMessageStateList[0]
            val oldMessageState = oldMessageStateRef.state.data


            val txCommand = Command(MessageContract.Commands.Delete(), oldMessageState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldMessageStateRef)
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
            val destinatarioSession = initiateFlow(oldMessageState.destinatario)
            val regolatoreSession = initiateFlow(oldMessageState.regolatore)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(destinatarioSession, regolatoreSession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(destinatarioSession, regolatoreSession), FINALISING_TRANSACTION.childProgressTracker()))
            return oldMessageState
        }

        @InitiatedBy(Deleter::class)
        class DeleterAcceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        "Cannot have output message" using (stx.tx.outputs.isEmpty())
                        /* "other rule message" using (output is regola_nuova) */
                    }
                }
                val txId = subFlow(signTransactionFlow).id

                return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
            }
        }
    }

    fun RespectRule(mittente: Party, destinatario: Party, rule: BlacklistState) : Boolean {
        if (mittente.name.organisation == "MemberA" && destinatario.name.organisation == "MemberB"){
            return rule.m1_m2
        }
        else if (mittente.name.organisation == "MemberA" && destinatario.name.organisation == "MemberC"){
            return rule.m1_m3
        }
        else if (mittente.name.organisation == "MemberB" && destinatario.name.organisation == "MemberA"){
            return rule.m2_m1
        }
        else if (mittente.name.organisation == "MemberB" && destinatario.name.organisation == "MemberC"){
            return rule.m2_m3
        }
        else if (mittente.name.organisation == "MemberC" && destinatario.name.organisation == "MemberA"){
            return rule.m3_m1
        }
        else if (mittente.name.organisation == "MemberC" && destinatario.name.organisation == "MemberB"){
            return rule.m3_m2
        }
        else return false
    }
}
