import java.util.ArrayList
import scala.util.Random
import akka.actor.Actor
import akka.routing.BroadcastGroup
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.routing.RoundRobinGroup
import akka.routing.Broadcast
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import akka.actor.Cancellable

object GossipSimulator {
  val MAX_RUMOUR_RECEIVE_TIME = 100
  val RATIO_STABLE_TIME = 3
  val THRESHOLD : Double = Math.pow(10, -10)

  val LINE = "line"
  val PERFECT_3D = "3d"
  val IMPERFECT_3D = "imp3d"
  val FULL = "full"
  val TOPOLOGY = List(LINE, PERFECT_3D, IMPERFECT_3D, FULL)

  val GOSSIP = "gossip"
  val PUSH_SUM = "push-sum"

  val ALG = List(GOSSIP, PUSH_SUM)
  val DIRECTIONX = List( -1, 1,  0, 0,  0, 0)
  val DIRECTIONY = List(  0, 0, -1, 1,  0, 0)
  val DIRECTIONZ = List(  0, 0,  0, 0, -1, 1)



  case object Start
  case object NodeStoped
  case object SendRoumor  
  case object KeepSpreading
  case class PushSum(si :Double, wi : Double)
  case class AddNeighbor(neighbor : ArrayList[ActorRef])
  case class PushSumFinished(ratio : Double)
  case object NodeConverged
  case object DeleteNeighbor
  case object KeepKilling
  case class Suicide(algo : String)
  
  // For testing
  case object SHOW 

  def main(args : Array[String]) {
    var (numOfNodes, topology, algorithm, deadPercent) = readArgs(args)
    
    // Round num of nodes
    if (topology.equals(PERFECT_3D) || topology.equals(IMPERFECT_3D)) {
      numOfNodes = Math.pow(Math.cbrt(numOfNodes).toInt + 1, 3).toInt
    }
    println("num of nodes after round is " + numOfNodes + "\n")

    val systemCongif = ActorSystem("GossipSimulator",ConfigFactory.load())
    var admin = systemCongif.actorOf(Props(classOf[Admin], numOfNodes, topology, algorithm, deadPercent), "Admin")
    admin ! Start
  }

  class Admin(numOfNodes : Int, topology : String, algorithm : String,  deadPercent: Double) extends Actor {
    var nodes : ArrayList[ActorRef] = new ArrayList[ActorRef]()
    var startTime : Long = 0;
    var endTime : Long = 0;
    var nodeConverged : Int = 0;

    /*Modified By Zhongyan QIU*/
    var killer: Cancellable = null
    var totalDeadNodes  = 0
    var numNodesDead = 0
    var systemShutdown = false;
    //End Modified By Zhongyan QIU


    def receive = {
      case Start => {
        for(i <- 0 to numOfNodes - 1) {
          val nodeID : String = "Node" + i
          nodes.add(i, context.actorOf(Props(classOf[Node], i), name = nodeID))
        }
        buildGraph(nodes, topology)

        // show neighbor of node
        //for(i <- 0 to nodes.size() - 1) {
        //  nodes.get(i) ! SHOW
        //  Thread.sleep(1000)
        //}

        startTime = System.currentTimeMillis()
        if(algorithm.equals(GOSSIP)) {
          selectRandomNode(nodes) ! SendRoumor
        } 
        if(algorithm.equals(PUSH_SUM)) {
          selectRandomNode(nodes) ! PushSum(0, 0)
        }

        /*Modified By Zhongyan QIU*/  
        totalDeadNodes = (Math.ceil(numOfNodes * deadPercent/100)).toInt

        println("I will kill "+totalDeadNodes+" Nodes! with percent " +deadPercent+".")//for test
        val system = context.system
        import system.dispatcher
        killer = system.scheduler.schedule(Duration.Zero, Duration(1, TimeUnit.MILLISECONDS), self, KeepKilling )
         //End Modified By Zhongyan QIU


      }

      case PushSumFinished(ratio : Double) => {
        if(systemShutdown == false){
          systemShutdown = true
          println("Average of the graph is ratio : " + ratio)
          context.system.shutdown()
        }
      }
      case NodeStoped => {
        if (nodes.contains(sender)) {
          nodes.remove(sender)
        }        
        if (nodes.isEmpty()) {
          endTime = System.currentTimeMillis()
          println("Finish time: " + (endTime - startTime) + " ms")
          context.system.shutdown()
        }
      }

      case NodeConverged => {
          
          if(nodeConverged < numOfNodes){
            nodeConverged = nodeConverged + 1
            println("Node convered " + nodeConverged)
          }

          if(nodeConverged == numOfNodes&&endTime==0){
            println("Message travel through whole graph!")
            endTime = System.currentTimeMillis()
            println("Converged time: " + (endTime - startTime) + " ms")
            context.system.shutdown()
          }
      }
      
      /*Modified By Zhongyan QIU*/  
      case KeepKilling => {
        if(numNodesDead < totalDeadNodes ){
          nodeConverged += 1
          numNodesDead += 1
          selectRandomNode(nodes)! Suicide(algorithm) 
        }else{
          killer.cancel()
        }
      }
      //End Modified By Zhongyan QIU

    }
    /*
    def shutdownSystem():Long = {
        context.system.shutdown()
        return System.currentTimeMillis()
    }*/

    def toOneDimention(x : Int, y : Int, z : Int, size : Int) : Int  = {
      if( x < 0 || x >= size || y < 0 || y >= size || z < 0 || z >= size) {
        return - 1
      }
      return size * size * x + size * y + z
    }

    def buildGraph(nodes : ArrayList[ActorRef], topology : String) {
      val cubeSize : Int = Math.cbrt(nodes.size()).toInt
      var neighbors : ArrayList[ActorRef] = null

      topology match {
        case LINE => {          
          for(i <- 0 to nodes.size() - 1){
            neighbors = new ArrayList[ActorRef]()
            if(i - 1 >= 0) {
              neighbors.add(nodes.get(i - 1))
            }
            if(i + 1 < nodes.size()) {
              neighbors.add(nodes.get(i + 1))
            }
            nodes.get(i) ! AddNeighbor(neighbors)
          }
        }

        case PERFECT_3D => {
          println("The size of cube is : " + cubeSize)

          for(i <- 0 to cubeSize - 1) {
            for(j <- 0 to cubeSize - 1) {
              for(k <- 0 to cubeSize - 1) {
                neighbors = new ArrayList[ActorRef]()
                var nodeIndex : Int = -1
                for(direct <- 0 to DIRECTIONX.length - 1) {
                  nodeIndex = toOneDimention(i + DIRECTIONX(direct), j + DIRECTIONY(direct), k + DIRECTIONZ(direct), cubeSize)
                  if(nodeIndex != -1) {
                    neighbors.add(nodes.get(nodeIndex))
                  }
                }
                nodes.get(toOneDimention(i, j, k, cubeSize)) ! AddNeighbor(neighbors)
              }
            }
          }
        }

        case IMPERFECT_3D => {
          println("The size of cube is : " + cubeSize)
          for(i <- 0 to cubeSize - 1) {
            for(j <- 0 to cubeSize - 1) {
              for(k <- 0 to cubeSize - 1) {
                neighbors = new ArrayList[ActorRef]()
                var nodeIndex : Int = -1
                for(direct <- 0 to DIRECTIONX.length - 1) {
                  nodeIndex = toOneDimention(i + DIRECTIONX(direct), j + DIRECTIONY(direct), k + DIRECTIONZ(direct), cubeSize)
                  if(nodeIndex != -1) {
                    neighbors.add(nodes.get(nodeIndex))
                  }
                }
                neighbors.add(selectRandomNode(nodes))
                nodes.get(toOneDimention(i, j, k, cubeSize)) ! AddNeighbor(neighbors)
              }
            }
          }          
        }

        case FULL => {
          for(i <- 0 to nodes.size() - 1) {
            neighbors = new ArrayList[ActorRef]()
            neighbors.addAll(nodes)
            neighbors.remove(nodes.get(i))
            nodes.get(i) ! AddNeighbor(neighbors)
          }
        }

      }
    }
  }

  class Node(nodeID : Int) extends Actor {
    var s : Double = nodeID.toDouble
    var w : Double = 1.0
    var rumourReceived = MAX_RUMOUR_RECEIVE_TIME
    var neighbors = new ArrayList[ActorRef]()
    var stableTime : Int = 0 

    val ID = nodeID
    /*Modified By Zhongyan QIU*/ 
    var watcher : Cancellable = null
    var nodeUp = true
    //End Modified By Zhongyan QIU
    def receive = {
      case SendRoumor => {
        //println(self + " ! rumour received " + rumourReceived)
        if (rumourReceived == MAX_RUMOUR_RECEIVE_TIME) {
          context.parent ! NodeConverged
        }
        var next = selectRandomNode(neighbors)
        //println("Messge send to " + next)
        if( next != null) {
          next ! SendRoumor  
        }
        rumourReceived = rumourReceived - 1
        /*Modified By Zhongyan QIU*/ 
        if(rumourReceived == MAX_RUMOUR_RECEIVE_TIME-1){
          val system = context.system
          import system.dispatcher
          watcher = system.scheduler.schedule(Duration(500, TimeUnit.MILLISECONDS), Duration(500, TimeUnit.MILLISECONDS), self, KeepSpreading)
        //End Modified By Zhongyan QIU 
        }else if(rumourReceived == 0) {
          sender ! DeleteNeighbor
          context.parent ! NodeStoped
        }         
      }
      /*Modified By Zhongyan QIU*/
      case KeepSpreading => {
        if (rumourReceived >0&&rumourReceived <= MAX_RUMOUR_RECEIVE_TIME) {
          var next = selectRandomNode(neighbors)
          if( next != null) {
            next ! SendRoumor  
          }
        }else if(rumourReceived == 0) {
          if (watcher != null && !watcher.isCancelled) {
            watcher.cancel()
          }
          //self ! DeleteNeighbor
          //context.parent ! NodeStoped
        }
      }
      //End Modified By Zhongyan QIU

      case PushSum(si, wi) => {
        if (nodeUp) {
          var oldRatio : Double = s / w
          var newRatio : Double = (s + si) / (w + wi)
          if(Math.abs(oldRatio - newRatio) <= THRESHOLD){
            stableTime = stableTime + 1
            if(stableTime >= 3) {
              context.parent ! PushSumFinished(newRatio)
            }
          } else {
            stableTime = 0
          }
          s = s + si
          w = w + wi
          s = s / 2
          w = w / 2
          selectRandomNode(neighbors) ! PushSum(s, w)
        }else{
          selectRandomNode(neighbors) ! PushSum(si, wi)
        }



      }

      case AddNeighbor(newNeighbors) => {
        neighbors.addAll(newNeighbors)
      }
      case DeleteNeighbor => {
        neighbors.remove(sender)
        if (neighbors.size() == 0) {
          context.parent ! NodeStoped
        }
      }
      case SHOW => {
        println("The neighbor of node " + self + " is")
        for(i <- 0 to neighbors.size() - 1) {
          println(neighbors.get(i))
        }
        println("END")
      }

      /*Modified By Zhongyan QIU*/
      case Suicide(algo) => {
        algo match {
          case GOSSIP => {
            if(rumourReceived != 0){
              //println("Kill One!")
              rumourReceived = 0
            }
          }
          
          case PUSH_SUM => {
           if(nodeUp != false){
              //println("Kill One!")
              nodeUp = false
            }
          }
        }
      }
      //End Modified By Zhongyan QIU

    }
  }
  def readArgs(args : Array[String]) : (Int, String, String, Double) = {
    var flag : Boolean = true
    var numOfNodes : Int = 0
    var topology : String = ""
    var algorithm : String = ""
    var deadPercent: Double = 0.0

    try {
      numOfNodes = args(0).toInt
      topology = args(1).toLowerCase()

      if(!TOPOLOGY.contains(topology)) {
        throw new Exception()
      }
      algorithm = args(2).toLowerCase()
      if(!ALG.contains(algorithm)) {
        throw new Exception()
      }
      deadPercent= args(3).toDouble
      if(!(deadPercent >= 0 && deadPercent <= 100)) {
          throw new Exception()
        }

    } catch {
      case ex : Exception => {
        println("Invalid arguments! Program force quit!")
        System.exit(0)
      }
    }
    return (numOfNodes, topology, algorithm, deadPercent)
  }

  def selectRandomNode(nodes : ArrayList[ActorRef]) : ActorRef = {
    val randomGenerator = new Random()
    if (nodes.size() == 0) {
      return null
    }
    val randomNum = randomGenerator.nextInt(nodes.size())
  //  println("Random select node is : " + randomNum)
    return nodes.get(randomNum)
  }
}