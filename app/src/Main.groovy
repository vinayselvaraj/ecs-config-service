import org.apache.log4j.*
import groovy.util.logging.*
import com.amazonaws.services.ecs.*
import com.amazonaws.services.ecs.model.*
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*

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

if(taskArns.size > 0) {

  // Describe Tasks
  def descTasksRequest = new DescribeTasksRequest().withTasks(taskArns)
  def descTasksResult = ecsClient.describeTasks(descTasksRequest)
  def tasks = descTasksResult.tasks
  
  // Put task containers in a map
  def taskContainerInstanceMap = new HashMap()
  tasks.each { task->
    def taskContainers = taskContainerInstanceMap.get(task.containerInstanceArn)
    if(taskContainers == null) {
      taskContainers = new ArrayList()
    }
    taskContainers.addAll(task.containers)
    
    taskContainerInstanceMap.put(task.containerInstanceArn, taskContainers)
  }
  
  // Get container instances
  def descContainerInstancesResult = ecsClient.describeContainerInstances(
    new DescribeContainerInstancesRequest()
      .withContainerInstances(taskContainerInstanceMap.keySet()))
  def containerInstances = descContainerInstancesResult.containerInstances
  
  // Put container instances in a HashMap to lookup by Arn
  def containerInstanceMap = new HashMap()
  containerInstances.each { containerInstance ->
    containerInstanceMap.put(containerInstance.containerInstanceArn, containerInstance)
  }
  
  // Get EC2 instances for each container instance
  def ec2Client = new AmazonEC2Client()
  def ec2InstanceIds = new ArrayList()
  containerInstances.each { containerInstance ->
    ec2InstanceIds.add(containerInstance.ec2InstanceId)
  }
  def descInstancesResult = ec2Client.describeInstances(
    new DescribeInstancesRequest().withInstanceIds(ec2InstanceIds))
  
  // Put EC2 instances in a map
  def ec2InstanceMap = new HashMap()
  descInstancesResult.reservations.each { reservation->
    reservation.instances.each { instance->
      ec2InstanceMap.put(instance.instanceId, instance)
    }
  }
  
  
  taskContainerInstanceMap.keySet().each { taskContainerInstanceArn ->
    def taskContainers = taskContainerInstanceMap.get(taskContainerInstanceArn)
    def containerInstance = containerInstanceMap.get(taskContainerInstanceArn)
    //println taskContainers
    //println containerInstance
    println ec2InstanceMap.get(containerInstance.ec2InstanceId).privateIpAddress
  }
  
}

