package com.example.contract

import com.example.state.MessageState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey
import java.time.Instant

class MessageContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.example.contract.MessageContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()
        for(command in commands){
            val setOfSigners = command.signers.toSet()
            when(command.value){
                is Commands.Create -> verifyCreate(tx, setOfSigners)
                is Commands.Issue -> verifyIssue(tx, setOfSigners)
                is Commands.Delete -> verifyDelete(tx, setOfSigners)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun verifyCreate(tx: LedgerTransaction, signers: Set<PublicKey>) {
        val command = tx.commands.requireSingleCommand<Commands.Create>()
        requireThat {
            // Generic constraints around the generic create transaction.
            "No inputs should be consumed" using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<MessageState>().single()
            "All of the participants must be signers." using (signers.containsAll(out.participants.map { it.owningKey }))

            // Message-specific constraints.
            "The regulator must be FCA." using  (out.regolatore.name.organisation == "FCA")
            "FCA cannot be the mittente." using (out.mittente.name.organisation != "FCA")
            "FCA cannot be the destinatario." using (out.destinatario.name.organisation != "FCA")
            "time cannot be in the future." using (out.time < Instant.now())
            "msg cannot be empty." using (out.msg.isNotEmpty())
            "sicurezza must be between 1 and 5" using (out.sicurezza in 1..5)
            "mittente and destinatario cannot be the same entity." using (out.mittente != out.destinatario)

        }
    }

    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Issue>()
        requireThat {
            // Generic constraints around the old Message transaction.
            "there must be only one message input." using (tx.inputs.size == 1)
            val oldMessageState = tx.inputsOfType<MessageState>().single()

            // Generic constraints around the generic issue transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newMessageState = tx.outputsOfType<MessageState>().single()
            "All of the participants must be signers." using (signers.containsAll(newMessageState.participants.map { it.owningKey }))

            // Generic constraints around the new Message transaction
            "old destinatario's message must be new mittente's message" using (oldMessageState.destinatario == newMessageState.mittente)
            "FCA cannot be the destinatario." using (newMessageState.destinatario.name.organisation != "FCA")
            "time cannot be in the future." using (newMessageState.time < Instant.now())
            "mittente and destinatario cannot be the same entity." using (newMessageState.mittente != newMessageState.destinatario)
        }
    }

    private fun verifyDelete(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Delete>()
        requireThat {
            // Generic constraints around the old Message transaction.
            "there must be only one message input." using (tx.inputs.size == 1)
            val oldMessageState = tx.inputsOfType<MessageState>().single()

            // Generic constraints around the generic delete transaction.
            "transaction state should't be created." using (tx.outputs.isEmpty())
            "All of the participants must be signers." using (signers.containsAll(oldMessageState.participants.map { it.owningKey }))
        }
    }

    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        class Create : Commands, TypeOnlyCommandData()
        class Issue: Commands, TypeOnlyCommandData()
        class Delete: Commands, TypeOnlyCommandData()
    }
}
