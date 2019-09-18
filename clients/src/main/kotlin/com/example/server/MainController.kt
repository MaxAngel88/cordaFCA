package com.example.server

import com.example.flow.BlacklistFlow.IssuerBlacklist
import com.example.flow.BlacklistFlow.StarterBlacklist
import com.example.flow.ExampleFlow.Initiator
import com.example.flow.MessageFlow.Deleter
import com.example.flow.MessageFlow.Starter
import com.example.state.BlacklistState
import com.example.state.IOUState
import com.example.state.MessageState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pojo.BlacklistPojo
import pojo.ResponsePojo
import javax.servlet.http.HttpServletRequest

val SERVICE_NAMES = listOf("Notary", "Network Map Service")

/**
 *  A Spring Boot Server API controller for interacting with the node via RPC.
 */

@RestController
@RequestMapping("/api/example/") // The paths for GET and POST requests are relative to this base path.
class MainController(rpc: NodeRPCConnection) {

    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    private val myLegalName = rpc.proxy.nodeInfo().legalIdentities.first().name
    private val proxy = rpc.proxy

    /**
     * Returns the node's name.
     */
    @GetMapping(value = [ "me" ], produces = [ APPLICATION_JSON_VALUE ])
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the network map service. These names can be used to look up identities using
     * the identity service.
     */
    @GetMapping(value = [ "peers" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getPeers(): Map<String, List<CordaX500Name>> {
        val nodeInfo = proxy.networkMapSnapshot()
        return mapOf("peers" to nodeInfo
                .map { it.legalIdentities.first().name }
                //filter out myself, notary and eventual network map started by driver
                .filter { it.organisation !in (SERVICE_NAMES + myLegalName.organisation) })
    }

    /**
     * Displays all IOU states that exist in the node's vault.
     */
    @GetMapping(value = [ "ious" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getIOUs() : ResponseEntity<List<StateAndRef<IOUState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<IOUState>().states)
    }

    /**
     * Initiates a flow to agree an IOU between two parties.
     *
     * Once the flow finishes it will have written the IOU to ledger. Both the lender and the borrower will be able to
     * see it when calling /spring/api/ious on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */

    @PostMapping(value = [ "create-iou" ], produces = [ TEXT_PLAIN_VALUE ], headers = [ "Content-Type=application/x-www-form-urlencoded" ])
    fun createIOU(request: HttpServletRequest): ResponseEntity<String> {
        val iouValue = request.getParameter("iouValue").toInt()
        val partyName = request.getParameter("partyName")
        if(partyName == null){
            return ResponseEntity.badRequest().body("Query parameter 'partyName' must not be null.\n")
        }
        if (iouValue <= 0 ) {
            return ResponseEntity.badRequest().body("Query parameter 'iouValue' must be non-negative.\n")
        }
        val partyX500Name = CordaX500Name.parse(partyName)
        val otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name) ?: return ResponseEntity.badRequest().body("Party named $partyName cannot be found.\n")

        return try {
            val signedTx = proxy.startTrackedFlow(::Initiator, iouValue, otherParty).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body("Transaction id ${signedTx.id} committed to ledger.\n")

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ex.message!!)
        }
    }

    /**
     * Displays all IOU states that only this node has been involved in.
     */
    @GetMapping(value = [ "my-ious" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getMyIOUs(): ResponseEntity<List<StateAndRef<IOUState>>>  {
        val myious = proxy.vaultQueryBy<IOUState>().states.filter { it.state.data.lender.equals(proxy.nodeInfo().legalIdentities.first()) }
        return ResponseEntity.ok(myious)
    }

    /**
     *
     * MESSAGE API -----------------------------------------------------------------------------------------------
     *
     */

    /**
     * Displays all MessageStates that exist in the node's vault.
     */
    @GetMapping(value = [ "messages" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getMessages() : ResponseEntity<List<StateAndRef<MessageState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<MessageState>().states)
    }

    /**
     * Initiates a flow to agree an Message between two parties.
     *
     * Once the flow finishes it will have written the Message to ledger. Both Mittente, Destinatario, Regolatore are able to
     * see it when calling /spring/api/messages on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */

    @PostMapping(value = [ "create-message" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/x-www-form-urlencoded" ])
    fun createMessage(request: HttpServletRequest): ResponseEntity<ResponsePojo> {
        val destinatario = request.getParameter("destinatario")
        val regolatore = request.getParameter("regolatore")
        val msg = request.getParameter("msg").toString()
        val sicurezza = request.getParameter("sicurezza").toInt()

        if(destinatario.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "destinatario cannot be empty", data = null))
        }

        if(regolatore.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "regolatore cannot be empty", data = null))
        }

        val destinatarioX500Name = CordaX500Name.parse(destinatario)
        val regolatoreX500Name = CordaX500Name.parse(regolatore)


        val destinatarioParty = proxy.wellKnownPartyFromX500Name(destinatarioX500Name) ?: return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "Party named $destinatario cannot be found.\n", data = null))
        val regolatoreParty = proxy.wellKnownPartyFromX500Name(regolatoreX500Name) ?: return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "Party named $regolatore cannot be found.\n", data = null))

        return try {
            val message = proxy.startTrackedFlow(::Starter, destinatarioParty, regolatoreParty, msg, sicurezza).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${message.linearId.id} committed to ledger.\n", data = message))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     * Displays all Message states that only this node has been involved in.
     */
    @GetMapping(value = [ "received-message" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getReceivedMessages(): ResponseEntity<List<StateAndRef<MessageState>>>  {
        val mymessages = proxy.vaultQueryBy<MessageState>().states.filter { it.state.data.destinatario.equals(proxy.nodeInfo().legalIdentities.first()) }
        return ResponseEntity.ok(mymessages)
    }

    /**
     * Delete a Message states between nodes.
     */
    @PostMapping(value = [ "delete-message" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/x-www-form-urlencoded" ])
    fun deleteMessage(request: HttpServletRequest): ResponseEntity<ResponsePojo> {
        val messageLinearId = request.getParameter("messageLinearId").toString()

        if(messageLinearId.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "messageLinearId cannot be empty", data = null))
        }

        return try {
            val message = proxy.startTrackedFlow(::Deleter, messageLinearId).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Message with id ${message.linearId.id} deleted correctly.. ledger updated..\n", data = message))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /***
     *
     * BLACKLIST API ------------------------------------------------------------------------------------------------
     *
     */

    /**
     * Displays all MessageStates that exist in the node's vault.
     */
    @GetMapping(value = [ "all-blacklist" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getAllBlacklist() : ResponseEntity<List<StateAndRef<BlacklistState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<BlacklistState>().states)
    }

    /**
     * Initiates a flow to create Blacklist configuration to permit/negate comunication between two parties.
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PostMapping(value = [ "create-blacklist" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/x-www-form-urlencoded" ])
    fun createBlacklist(request: HttpServletRequest): ResponseEntity<ResponsePojo> {
        val memberA = request.getParameter("memberA")
        val memberB = request.getParameter("memberB")
        val memberC = request.getParameter("memberC")
        val m1_m2 = request.getParameter("m1_m2").toBoolean()
        val m1_m3 = request.getParameter("m1_m3").toBoolean()
        val m2_m3 = request.getParameter("m2_m3").toBoolean()
        val m2_m1 = request.getParameter("m2_m1").toBoolean()
        val m3_m1 = request.getParameter("m3_m1").toBoolean()
        val m3_m2 = request.getParameter("m3_m2").toBoolean()
        val info: String = request.getParameter("info")

        if(memberA.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "memberA cannot be empty", data = null))
        }

        if(memberB.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "memberB cannot be empty", data = null))
        }

        if(memberC.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "memberC cannot be empty", data = null))
        }

        if(info.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "info cannot be empty", data = null))
        }

        val memberAX500Name = CordaX500Name.parse(memberA)
        val memberBX500Name = CordaX500Name.parse(memberB)
        val memberCX500Name = CordaX500Name.parse(memberC)

        val memberAParty = proxy.wellKnownPartyFromX500Name(memberAX500Name) ?: return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "Party named $memberA cannot be found.\n", data = null))
        val memberBParty = proxy.wellKnownPartyFromX500Name(memberBX500Name) ?: return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "Party named $memberB cannot be found.\n", data = null))
        val memberCParty = proxy.wellKnownPartyFromX500Name(memberCX500Name) ?: return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "Party named $memberC cannot be found.\n", data = null))


        val property = BlacklistPojo(m1_m2, m1_m3, m2_m3, m2_m1, m3_m1, m3_m2, info)

        return try {
            val blacklist = proxy.startTrackedFlow(::StarterBlacklist, memberAParty, memberBParty, memberCParty, property).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${blacklist.linearId.id} committed to ledger. Blacklist state created.\n", data = blacklist))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /***
     *
     * Update Blacklist
     *
     */
    @PostMapping(value = [ "update-blacklist" ], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/x-www-form-urlencoded" ])
    fun updateBlacklist(request: HttpServletRequest): ResponseEntity<ResponsePojo> {
        val blacklistLinearId = request.getParameter("blacklistLinearId")
        val m1_m2 = request.getParameter("m1_m2").toBoolean()
        val m1_m3 = request.getParameter("m1_m3").toBoolean()
        val m2_m3 = request.getParameter("m2_m3").toBoolean()
        val m2_m1 = request.getParameter("m2_m1").toBoolean()
        val m3_m1 = request.getParameter("m3_m1").toBoolean()
        val m3_m2 = request.getParameter("m3_m2").toBoolean()
        val info: String = request.getParameter("info")

        if(blacklistLinearId.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "blacklistLinearId cannot be empty", data = null))
        }

        if(info.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "info cannot be empty", data = null))
        }

        val property = BlacklistPojo(m1_m2, m1_m3, m2_m3, m2_m1, m3_m1, m3_m2, info)

        return try {
            val updateBlacklist = proxy.startTrackedFlow(::IssuerBlacklist, blacklistLinearId, property).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Blacklist with id: ${blacklistLinearId} update correctly" + "New Blacklist with id: ${updateBlacklist.linearId.id}  created.. ledger updated.\n", data = updateBlacklist))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }
}
