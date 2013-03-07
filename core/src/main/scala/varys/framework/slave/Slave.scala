package varys.framework.slave

import java.nio.{ByteBuffer, MappedByteBuffer}
import java.nio.channels.FileChannel
import java.io.{File, RandomAccessFile, ObjectInputStream, ObjectOutputStream}
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.mutable.{ArrayBuffer, HashMap}

import akka.actor.{ActorRef, Address, Props, Actor, ActorSystem, Terminated}
import akka.util.duration._
import akka.remote.{RemoteClientLifeCycleEvent, RemoteClientShutdown, RemoteClientDisconnected}

import varys.framework.master.Master
import varys.{VarysCommon, Logging, Utils, VarysException}
import varys.util.AkkaUtils
import varys.framework._
import varys.network._

import org.hyperic.sigar.{Sigar, SigarException, NetInterfaceStat}

private[varys] class SlaveActor(
    ip: String,
    port: Int,
    webUiPort: Int,
    commPort: Int,
    masterUrl: String,
    workDirPath: String = null)
  extends Actor with Logging {
  
  val recvMan = new ConnectionManager(commPort)
  recvMan.onReceiveMessage((msg: Message, id: ConnectionManagerId) => {
    logInfo("Inside recvMan" + msg)

    // Parse request
    val byteArr = Message.getBytes(msg.asInstanceOf[BufferMessage])
    val req = Utils.deserialize[GetRequest](byteArr)
    
    var buffer: ByteBuffer = null
    req.flowDesc.flowType match {
      case FlowType.FAKE => {
        // Create Data
        val size = req.flowDesc.sizeInBytes.toInt
        buffer = ByteBuffer.allocate(size).put(Array.tabulate[Byte](size)(_.toByte))
        buffer.flip
      }
      
      case FlowType.ONDISK => {
        // Read data from file into memory and send it
        val fileDesc = req.flowDesc.asInstanceOf[FileDescription]
        val inChannel = new RandomAccessFile(fileDesc.pathToFile, "r").getChannel()
        buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size())
        inChannel.close()
      }
      
      case FlowType.INMEMORY => {
        
      }
      
      case _ => {
        logError("Invalid FlowType!")
        System.exit(1)
      }
    }
    
    // Send
    Some(Message.createBufferMessage(buffer.duplicate))
  })

  // TODO: Keep track of local data
  val idsToFlow = new HashMap[(String, String), FlowDescription]

  val DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss")  // For slave IDs

  var master: ActorRef = null
  var masterAddress: Address = null
  
  var masterWebUiUrl : String = ""
  val slaveId = generateSlaveId()
  var varysHome: File = null
  var workDir: File = null
  val publicAddress = {
    val envVar = System.getenv("VARYS_PUBLIC_DNS")
    if (envVar != null) envVar else ip
  }

  var sigar = new Sigar()
  var lastRxBytes = -1.0
  var lastTxBytes = -1.0
  
  var curRxBps = 0.0
  var curTxBps = 0.0

  def createWorkDir() {
    workDir = Option(workDirPath).map(new File(_)).getOrElse(new File(varysHome, "work"))
    try {
      if (!workDir.exists() && !workDir.mkdirs()) {
        logError("Failed to create work directory " + workDir)
        System.exit(1)
      }
    } catch {
      case e: Exception =>
        logError("Failed to create work directory " + workDir, e)
        System.exit(1)
    }
  }

  override def preStart() {
    logInfo("Starting Varys slave %s:%d".format(ip, port))
    varysHome = new File(Option(System.getenv("VARYS_HOME")).getOrElse("."))
    logInfo("Varys home: " + varysHome)
    createWorkDir()
    connectToMaster()
    startWebUi()
  }

  def connectToMaster() {
    logInfo("Connecting to master " + masterUrl)
    try {
      master = context.actorFor(Master.toAkkaUrl(masterUrl))
      masterAddress = master.path.address
      master ! RegisterSlave(slaveId, ip, port, webUiPort, commPort, publicAddress)
      context.system.eventStream.subscribe(self, classOf[RemoteClientLifeCycleEvent])
      context.watch(master) // Doesn't work with remote actors, but useful for testing
    } catch {
      case e: Exception =>
        logError("Failed to connect to master", e)
        System.exit(1)
    }
  }

  def startWebUi() {
    val webUi = new SlaveWebUI(context.system, self)
    try {
      AkkaUtils.startSprayServer(context.system, "0.0.0.0", webUiPort, webUi.handler)
    } catch {
      case e: Exception =>
        logError("Failed to create web UI", e)
        System.exit(1)
    }
  }

  override def receive = {
    case RegisteredSlave(url) => {
      masterWebUiUrl = url
      logInfo("Successfully registered with master")

      // Thread to periodically uodate last{Rx|Tx}Bytes
      context.system.scheduler.schedule(0 millis, VarysCommon.HEARTBEAT_SEC * 1000 millis) {
        updateNetStats()
        master ! Heartbeat(slaveId, curRxBps, curTxBps)
      }
    }

    case RegisterSlaveFailed(message) => {
      logError("Slave registration failed: " + message)
      System.exit(1)
    }

    case Terminated(actor) if actor == master => {
      masterDisconnected()
    }
    
    case RemoteClientDisconnected(_, address) if address == masterAddress => {
      masterDisconnected()
    }
     
    case RemoteClientShutdown(_, address) if address == masterAddress => {
      masterDisconnected()
    }

    case RequestSlaveState => {
      sender ! SlaveState(ip, port, slaveId, masterUrl, curRxBps, curTxBps, masterWebUiUrl)
    }
    
    case RegisteredCoflow(coflowId) => {
      // TODO: Do something!
      sender ! true
    }
    
    case UnregisterCoflow(coflowId) => {
      // TODO: Do something!
      sender ! true
    }
    
    case AddFlow(flowDesc) => {
      // TODO: Do something!
      logInfo("Handling " + flowDesc)
      
      // Update commPort if the end point will be a client
      if (flowDesc.flowType != FlowType.INMEMORY) {
        flowDesc.updateCommPort(commPort)
      }
      
      // Now let the master know and notify the client
      AkkaUtils.tellActor(master, AddFlow(flowDesc))
      sender ! true
    }
    
    case GetFlow(flowId, coflowId, clientId, _, flowDesc) => {
      // TODO: Do something!
      
      sender ! true
    }
    
    case DeleteFlow(flowId, coflowId) => {
      // TODO: Actually remove
      sender ! true
    }
  }

  def masterDisconnected() {
    // TODO: It would be nice to try to reconnect to the master, but just shut down for now.
    // (Note that if reconnecting we would also need to assign IDs differently.)
    logError("Connection to master failed! Shutting down.")
    System.exit(1)
  }

  def generateSlaveId(): String = {
    "slave-%s-%s-%d".format(DATE_FORMAT.format(new Date), ip, port)
  }
  
  /**
   * Update last{Rx|Tx}Bytes before each heartbeat
   * Return the pair (rxBps, txBps)
   */
  def updateNetStats() = {
    var curRxBytes = 0.0;
    var curTxBytes = 0.0;
    
    try {
      val netIfs = sigar.getNetInterfaceList()
      for (i <- 0 until netIfs.length) {
        val net = sigar.getNetInterfaceStat(netIfs(i))
      
        val r = net.getRxBytes()
        if (r >= 0) {
          curRxBytes += r
        }
      
        val t = net.getTxBytes()
        if (t >= 0.0) {
          curTxBytes += t
        }
      }
    } catch {
      case se: SigarException => {
        println(se)
      }
    }
    
    var rxBps = 0.0
    var txBps = 0.0
    if (lastRxBytes >= 0.0 && lastTxBytes >= 0.0) {
      rxBps = (curRxBytes - lastRxBytes) / VarysCommon.HEARTBEAT_SEC;
      txBps = (curTxBytes - lastTxBytes) / VarysCommon.HEARTBEAT_SEC;
    } 
    
    lastRxBytes = curRxBytes
    lastTxBytes = curTxBytes
    
    curRxBps = rxBps
    curTxBps = txBps

    // FIXME: Sometimes Sigar stops responding, and printing something in this method brings it back
    // logInfo(rxBps + " " + txBps)
  }
}

private[varys] object Slave {
  private val systemName = "varysSlave"
  private val actorName = "Slave"
  private val varysUrlRegex = "varys://([^:]+):([0-9]+)".r

  def main(argStrings: Array[String]) {
    val args = new SlaveArguments(argStrings)
    val (actorSystem, _) = startSystemAndActor(args.ip, args.port, args.webUiPort, args.commPort,
      args.master, args.workDir)
    actorSystem.awaitTermination()
  }

  /** Returns an `akka://...` URL for the Master actor given a varysUrl `varys://host:ip`. */
  def toAkkaUrl(varysUrl: String): String = {
    varysUrl match {
      case varysUrlRegex(host, port) =>
        "akka://%s@%s:%s/user/%s".format(systemName, host, port, actorName)
      case _ =>
        throw new VarysException("Invalid master URL: " + varysUrl)
    }
  }

  def startSystemAndActor(host: String, port: Int, webUiPort: Int, commPort: Int,
    masterUrl: String, workDir: String, slaveNumber: Option[Int] = None): (ActorSystem, Int) = {
    // The LocalVarysCluster runs multiple local varysSlaveX actor systems
    val systemName = "varysSlave" + slaveNumber.map(_.toString).getOrElse("")
    val (actorSystem, boundPort) = AkkaUtils.createActorSystem(systemName, host, port)
    val actor = actorSystem.actorOf(Props(new SlaveActor(host, boundPort, webUiPort, commPort,
      masterUrl, workDir)), name = "Slave")
    (actorSystem, boundPort)
  }

}
