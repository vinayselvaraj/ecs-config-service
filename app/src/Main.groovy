import org.apache.log4j.*
import groovy.util.logging.*
import com.amazonaws.services.ecs.*
import com.amazonaws.services.ecs.model.*
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*

LOG = Logger.getInstance(getClass())

// Read cluster name from environment variable
ENV          = System.getenv()
CLUSTER_NAME = ENV['CLUSTER_NAME']

if(CLUSTER_NAME == null) {
  LOG.fatal("Environment variable CLUSTER_NAME cannot be null")
  System.exit(1)
}

// Initialize AWS Clients
ecsClient = new AmazonECSClient()
ec2Client = new AmazonEC2Client()

// Get all tasks in the cluster
def tasks = getTasksForCluster()

// Get a map of container instance arns to container instances
def containerInstanceMap = getContainerInstanceMap(tasks)

// Get a map of ec2 instance ids to ec2 instances
def ec2InstanceMap = getEc2InstanceMap(containerInstanceMap.values())

// Get map of task definitions <TaskDefinitionArn, TaskDefinition>
def taskDefinitionMap = getTaskDefinitionMap(tasks)

// Get list of container definitions that contain the ECS_CONFIG_HANDLER_URL env variable
def containerDefinitions = getValidContainerDefinitions(tasks, taskDefinitionMap)


def getValidContainerDefinitions(def tasks, def taskDefinitionMap) {
  tasks.each { task->
    def remove = false
        
    def taskDefinition = taskDefinitionMap.get(task.taskDefinitionArn)
    def containerDefinitions = taskDefinition.containerDefinitions
    containerDefinitions.each { containerDefiniton->
      def environmentVars = containerDefiniton.environment
      environmentVars.each { envVar->
        println envVar
      }
    }

    //println taskDefinition
  }
}

def getTasksForCluster() {
  
  // Get all task ARNs for the cluster
  def taskArns = new ArrayList()
  
  def token = null
  def done = false
  
  while(!done) {  
    ListTasksRequest listTasksRequest = new ListTasksRequest()
    listTasksRequest.cluster = CLUSTER_NAME
    listTasksRequest.nextToken = token
  
    def tasks = ecsClient.listTasks(listTasksRequest)
    taskArns.addAll(tasks.taskArns)
  
    if(token == null) {
      done = true
    } else {
      token = tasks.nextToken
    }
  }
  
  // Describe Tasks
  def descTasksRequest = new DescribeTasksRequest().withTasks(taskArns)
  def descTasksResult = ecsClient.describeTasks(descTasksRequest)
  
  return descTasksResult.tasks
}

def getTaskDefinitionMap(def tasks) {
  def taskDefinitionArns = new ArrayList()
  
  tasks.each { task->
    if(!taskDefinitionArns.contains(task.taskDefinitionArn)) {
      taskDefinitionArns.add(task.taskDefinitionArn)
    }
  }
  
  // Put task definitions in a map <taskDefinitonArn, taskDefinition>
  def taskDefinitionMap = new HashMap()
  taskDefinitionArns.each { taskDefinitionArn->
    def descTaskDefResult = ecsClient.describeTaskDefinition(
      new DescribeTaskDefinitionRequest().withTaskDefinition(taskDefinitionArn))
    taskDefinitionMap.put(taskDefinitionArn, descTaskDefResult.taskDefinition)
  }
  
  return taskDefinitionMap
}

def getContainerInstanceMap(def tasks) {
  
  def containerInstanceArns = new ArrayList()
  tasks.each { task->
    containerInstanceArns.add(task.containerInstanceArn)
  }

  // Get container instances
  def descContainerInstancesResult = ecsClient.describeContainerInstances(
    new DescribeContainerInstancesRequest()
      .withContainerInstances(containerInstanceArns))
  def containerInstances = descContainerInstancesResult.containerInstances
  
  // Put container instances in a HashMap to lookup by Arn
  def containerInstanceMap = new HashMap()
  containerInstances.each { containerInstance ->
    containerInstanceMap.put(containerInstance.containerInstanceArn, containerInstance)
  }
  
  return containerInstanceMap
}

def getEc2InstanceMap(def containerInstances) {
  
  // Get EC2 instances for each container instance
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
  
  return ec2InstanceMap
}

