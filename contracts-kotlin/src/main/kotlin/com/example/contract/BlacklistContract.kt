package com.example.contract

import com.example.state.BlacklistState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey
import java.time.Instant

class BlacklistContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.example.contract.BlacklistContract"
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
            val out = tx.outputsOfType<BlacklistState>().single()
            "All of the participants must be signers." using (signers.containsAll(out.participants.map { it.owningKey }))

            // Blacklist-specific constraints.
            "memberA must be different from memberB" using (out.memberA.name.organisation != out.memberB.name.organisation)
            "memberA must be different from memberC" using (out.memberA.name.organisation != out.memberC.name.organisation)
            "memberB must be different from memberC" using (out.memberB.name.organisation != out.memberC.name.organisation)
            "time cannot be in the future." using (out.time < Instant.now())
            "info cannot be empty." using (out.info.isNotEmpty())
        }
    }

    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>){
        val commands = tx.commands.requireSingleCommand<Commands.Issue>()
        requireThat {
            // Generic constraints around the old Blacklist transaction.
            "there must be only one blacklist input." using (tx.inputs.size == 1)
            val oldBlacklistState = tx.inputsOfType<BlacklistState>().single()

            // Generic constraints around the generic issue transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newBlacklistState = tx.outputsOfType<BlacklistState>().single()
            "All of the participants must be signers." using (signers.containsAll(newBlacklistState.participants.map { it.owningKey }))

            // Generic constraints around the new Blacklist transaction
            "old regolatore must be new regolatore" using (oldBlacklistState.regolatore == newBlacklistState.regolatore)
            "time cannot be in the future." using (newBlacklistState.time < Instant.now())
            "info cannot be empty." using (newBlacklistState.info.isNotEmpty())
        }
    }


    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        class Create : Commands, TypeOnlyCommandData()
        class Issue: Commands, TypeOnlyCommandData()
    }
}
