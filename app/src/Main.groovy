import org.apache.log4j.*
import groovy.util.logging.*
import com.amazonaws.services.ecs.*
import com.amazonaws.services.ecs.model.*

LOG = Logger.getInstance(getClass())

def env = System.getenv()
def cluster_name = env['CLUSTER_NAME']

if(cluster_name == null) {
  LOG.fatal("Environment variable CLUSTER_NAME cannot be null")
  System.exit(1)
}

def ecsClient = new AmazonECSClient()

List taskArns = new ArrayList()

// Get all task ARNs for the cluster
def token = null
def done = false
while(!done) {
  
  ListTasksRequest listTasksRequest = new ListTasksRequest()
  listTasksRequest.cluster = cluster_name
  listTasksRequest.nextToken = token
  
  def tasks = ecsClient.listTasks(listTasksRequest)
  taskArns.addAll(tasks.taskArns)
  
  if(token == null) {
    done = true
  } else {
    token = tasks.nextToken
  }
}

for(taskArn in taskArns) {
  println taskArn
}
