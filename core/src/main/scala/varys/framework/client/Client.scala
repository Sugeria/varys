package varys.framework.client

import java.io._
import java.net._

import scala.collection.mutable.HashMap

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.pattern.AskTimeoutException
import akka.remote.RemoteClientLifeCycleEvent
import akka.remote.RemoteClientShutdown
import akka.remote.RemoteClientDisconnected
import akka.actor.Terminated
import akka.dispatch.Await

import varys.{VarysCommon, VarysException, Logging}
import varys.framework._
import varys.framework.master.{Master, CoflowInfo}
import varys.framework.slave.Slave
import varys.util._
import varys.Utils

class Client(
    clientName: String,
    masterUrl: String,
    listener: ClientListener = null)
  extends Logging {

  val INTERNAL_ASK_TIMEOUT_MS: Int = System.getProperty("varys.framework.ask.wait", "5000").toInt

  var actorSystem: ActorSystem = null
  
  var masterActor: ActorRef = null
  val clientRegisterLock = new Object
  
  var slaveId: String = null
  var slaveUrl: String = null
  var slaveActor: ActorRef = null
  
  var clientId: String = null
  var clientActor: ActorRef = null

  val flowToTIS = new HashMap[DataIdentifier, ThrottledInputStream]
  val flowToBitPerSec = new HashMap[DataIdentifier, Double]
  val flowToObject = new HashMap[DataIdentifier, Array[Byte]]

  val serverThreadName = "ServerThread for Client@" + Utils.localHostName()
  var dataServer = new DataServer(0, serverThreadName, flowToObject)
  dataServer.start()

  var clientHost = Utils.localHostName()
  var clientCommPort = dataServer.getCommPort

  class ClientActor extends Actor with Logging {
    var masterAddress: Address = null
    var alreadyDisconnected = false  // To avoid calling listener.disconnected() multiple times

    override def preStart() {
      logInfo("Connecting to master " + masterUrl)
      try {
        masterActor = context.actorFor(Master.toAkkaUrl(masterUrl))
        masterAddress = masterActor.path.address
        masterActor ! RegisterClient(clientName, clientHost, clientCommPort)
        context.system.eventStream.subscribe(self, classOf[RemoteClientLifeCycleEvent])
        context.watch(masterActor)  // Doesn't work with remote actors, but useful for testing
      } catch {
        case e: Exception =>
          logError("Failed to connect to master", e)
          markDisconnected()
          context.stop(self)
      }
    }

    override def receive = {
      case RegisteredClient(clientId_, slaveId_, slaveUrl_) =>
        clientId = clientId_
        slaveId = slaveId_
        slaveUrl = slaveUrl_
        slaveActor = context.actorFor(Slave.toAkkaUrl(slaveUrl))
        if (listener != null) {
          listener.connected(clientId)
        }
        clientRegisterLock.synchronized { clientRegisterLock.notifyAll() }  // Ready to go!
        logInfo("Registered to master. Local slave url = " + slaveUrl)

      case Terminated(actor_) if actor_ == masterActor =>
        logError("Connection to master failed; stopping client")
        markDisconnected()
        context.stop(self)

      case RemoteClientDisconnected(_, address) if address == masterAddress =>
        logError("Connection to master failed; stopping client")
        markDisconnected()
        context.stop(self)

      case RemoteClientShutdown(_, address) if address == masterAddress =>
        logError("Connection to master failed; stopping client")
        markDisconnected()
        context.stop(self)

      case StopClient =>
        markDisconnected()
        sender ! true
        context.stop(self)
        
      case UpdatedRates(newRates) => 
        logInfo("Received updated shares")
        for ((flowDesc, newBitPerSec) <- newRates) {
          logInfo(flowDesc + " ==> " + newBitPerSec + " bps")
          flowToBitPerSec(flowDesc.dataId) = newBitPerSec
          if (flowToTIS.contains(flowDesc.dataId)) {
            flowToTIS(flowDesc.dataId).setNewRate(newBitPerSec)
          } 
        }
    }

    /**
     * Notify the listener that we disconnected, if we hadn't already done so before.
     */
    def markDisconnected() {
      if (!alreadyDisconnected) {
        if (listener != null) {
          listener.disconnected()
        }
        alreadyDisconnected = true
      }
    }
    
  }

  def start() {
    // Just launch an actor; it will call back into the listener.
    val (actorSystem_, _) = AkkaUtils.createActorSystem("varysClient", Utils.localIpAddress, 0)
    actorSystem = actorSystem_
    clientActor = actorSystem.actorOf(Props(new ClientActor))
  }

  def stop() {
    if (clientActor != null) {
      try {
        val timeout = INTERNAL_ASK_TIMEOUT_MS.millis
        val future = clientActor.ask(StopClient)(timeout)
        Await.result(future, timeout)
      } catch {
        case e: AskTimeoutException =>  // Ignore it, maybe master went away
      }
      clientActor = null
    }
    dataServer.stop()
  }
  
  def awaitTermination() { 
    actorSystem.awaitTermination() 
  }
  
  // Wait until the client has been registered
  private def waitForRegistration = {
    while (clientId == null) {
      clientRegisterLock.synchronized { 
        clientRegisterLock.wait() 
      }
    }
  }
  
  def registerCoflow(coflowDesc: CoflowDescription): String = {
    waitForRegistration
    
    // Register with the master
    val RegisteredCoflow(coflowId) = AkkaUtils.askActorWithReply[RegisteredCoflow](masterActor, 
      RegisterCoflow(clientId, coflowDesc))
      
    // Let the local slave know
    AkkaUtils.tellActor(slaveActor, RegisteredCoflow(coflowId))
    
    coflowId
  }
  
  def unregisterCoflow(coflowId: String) {
    waitForRegistration
    
    // Let the master know
    AkkaUtils.tellActor(masterActor, UnregisterCoflow(coflowId))
    
    // Update local slave
    AkkaUtils.tellActor(slaveActor, UnregisterCoflow(coflowId))
    
    // Free local resources
    flowToTIS.retain((dataId, _) => dataId.coflowId != coflowId)
    flowToBitPerSec.retain((dataId, _) => dataId.coflowId != coflowId)
    flowToObject.retain((dataId, _) => dataId.coflowId != coflowId)
  }

  /**
   * Makes data available for retrieval, and notifies local slave, which will register it with the master.
   * Non-blocking call.
   */
  private def handlePut(flowDesc: FlowDescription, serialObj: Array[Byte] = null) {
    waitForRegistration
    
    // Notify the slave, which will notify the master
    AkkaUtils.tellActor(slaveActor, AddFlow(flowDesc))
    
    // Keep a reference to the object to be served when asked for.
    if (flowDesc.dataType == DataType.INMEMORY) {
      assert(serialObj != null)
      flowToObject(flowDesc.dataId) = serialObj
    } 
  }

  /**
   * Puts any data structure
   */
  def putObject[T: Manifest](objId: String, obj: T, coflowId: String, size: Long, numReceivers: Int) {
    // TODO: Figure out class name
    val className = "UnknownType" 
    val desc = new ObjectDescription(objId, className, coflowId, DataType.INMEMORY, size, 
      numReceivers, clientHost, clientCommPort)
    val serialObj = Utils.serialize[T](obj)
    handlePut(desc, serialObj)
  }
  
  /**
   * Puts a complete local file
   */
  def putFile(fileId: String, pathToFile: String, coflowId: String, size: Long, numReceivers: Int) {
    putFile(fileId, pathToFile, coflowId, 0, size, numReceivers)
  }

  /**
   * Puts a range of local file
   */
  def putFile(fileId: String, pathToFile: String, coflowId: String, offset: Long, size: Long, numReceivers: Int) {
    val desc = new FileDescription(fileId, pathToFile, coflowId, DataType.ONDISK, offset, size, numReceivers, 
      clientHost, clientCommPort)
    handlePut(desc)
  }
  
  /**
   * Emulates the process without having to actually put anything
   */
  def putFake(blockId: String, coflowId: String, size: Long, numReceivers: Int) {
    val desc = new FlowDescription(blockId, coflowId, DataType.FAKE, size, numReceivers, 
      clientHost, clientCommPort)
    handlePut(desc)
  }
  
  /**
   * Notifies the master and the slave. But everything is done in the client
   * Blocking call.
   */
  private def handleGet(blockId: String, dataType: DataType.DataType, coflowId: String): Array[Byte] = {
    waitForRegistration
    
    // Notify master and retrieve the FlowDescription in response
    var flowDesc: FlowDescription = null

    val gotFlowDesc = AkkaUtils.askActorWithReply[Option[GotFlowDesc]](masterActor, 
      GetFlow(blockId, coflowId, clientId, slaveId))
    gotFlowDesc match {
      case Some(GotFlowDesc(x)) => flowDesc = x
      case None => { }
    }
    logInfo("Received " + flowDesc + " for " + blockId + " of coflow " + coflowId)
    
    if (flowDesc == null) {
      // TODO: Handle failure. Retry may be?
      logError("Failed to receive FlowDescription for " + blockId + " of coflow " + coflowId)
    }
    assert(flowDesc != null)
    
    // Notify local slave
    AkkaUtils.tellActor(slaveActor, GetFlow(blockId, coflowId, clientId, slaveId, flowDesc))
    
    // Get it!
    val sock = new Socket(flowDesc.originHost, flowDesc.originCommPort)
    val oos = new ObjectOutputStream(new BufferedOutputStream(sock.getOutputStream))
    oos.flush

    val tisRate = flowToBitPerSec.getOrElse(flowDesc.dataId, 0.0)
    val tis = new ThrottledInputStream(sock.getInputStream, tisRate)
    val ois = new ObjectInputStream(tis)
    
    flowToTIS(flowDesc.dataId) = tis
    
    oos.writeObject(GetRequest(flowDesc))
    oos.flush
    
    val resp = ois.readObject.asInstanceOf[Option[Array[Byte]]]
    var retVal: Array[Byte] = null
    resp match {
      case Some(byteArr) => {
        logInfo("Received response of " + byteArr.length + " bytes")
        
        dataType match {
          case DataType.FAKE => {
            // Throw away
          }

          case DataType.ONDISK => {
            retVal = byteArr
          }

          case DataType.INMEMORY => {
            retVal = byteArr
          }

          case _ => {
            logError("Invalid DataType!")
            throw new VarysException("Invalid DataType!")
          }
        }
      }
      case None => {
        logError("Nothing received!")
        throw new VarysException("Invalid DataType!")
      }
    }
    
    ois.close
    oos.close
    sock.close
    
    return retVal
  }
  
  /**
   * Retrieves data from any of the feasible locations. 
   */
  def getObject[T](objectId: String, coflowId: String): T = {
    val resp = handleGet(objectId, DataType.INMEMORY, coflowId)
    Utils.deserialize[T](resp)
  }
  
  /**
   * Gets a file
   */
  def getFile(fileId: String, coflowId: String): Array[Byte] = {
    handleGet(fileId, DataType.ONDISK, coflowId)
  }
  
  /**
   * Paired get() for putFake. Doesn't return anything, but emulates the retrieval process.
   */
  def getFake(blockId: String, coflowId: String) {
    handleGet(blockId, DataType.FAKE, coflowId)
  }
  
  def deleteFlow(flowId: String, coflowId: String) {
    AkkaUtils.tellActor(slaveActor, DeleteFlow(flowId, coflowId))
  }

  /**
   * Receive 'howMany' machines with the lowest incoming usage
   */
  def getBestRxMachines(howMany: Int, adjustBytes: Long): Array[String] = {
    val BestRxMachines(bestRxMachines) = AkkaUtils.askActorWithReply[BestRxMachines](masterActor,  
      RequestBestRxMachines(howMany, adjustBytes))
    bestRxMachines
  }

  /**
   * Receive the machine with the lowest incoming usage
   */
  def getBestRxMachine(adjustBytes: Long): String = {
    val BestRxMachines(bestRxMachines) = AkkaUtils.askActorWithReply[BestRxMachines](masterActor,  
      RequestBestRxMachines(1, adjustBytes))
    bestRxMachines(0)
  }

  /**
   * Receive 'howMany' machines with the lowest outgoing usage
   */
  def getTxMachines(howMany: Int, adjustBytes: Long): Array[String] = {
    val BestTxMachines(bestTxMachines) = AkkaUtils.askActorWithReply[BestTxMachines](masterActor,  
      RequestBestTxMachines(howMany, adjustBytes))
    bestTxMachines
  }
  
  /**
   * Receive the machine with the lowest outgoing usage
   */
  def getBestTxMachine(adjustBytes: Long): String = {
    val BestTxMachines(bestTxMachines) = AkkaUtils.askActorWithReply[BestTxMachines](masterActor,  
      RequestBestTxMachines(1, adjustBytes))
    bestTxMachines(0)
  }
}
