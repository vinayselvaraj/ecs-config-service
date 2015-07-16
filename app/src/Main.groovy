import org.apache.log4j.*

import java.util.concurrent.*

import org.apache.http.impl.client.*
import org.apache.http.entity.*
import org.apache.http.client.methods.*
import org.apache.http.client.config.RequestConfig
import groovy.util.logging.*
import groovy.json.*
import com.amazonaws.services.ecs.*
import com.amazonaws.services.ecs.model.*
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*

LOG = Logger.getInstance(getClass())

// Read cluster name from environment variable
ENV          = System.getenv()
CLUSTER_NAME = ENV['CLUSTER_NAME']

MAX_THREADS = 512

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

// Get map of container definitions
def containerDefinitionMap = getContainerDefinitionsMap(tasks, taskDefinitionMap)

// Initialize HttpClient
def httpClient = HttpClients.createDefault()
def httpRequestConfig = RequestConfig.custom()
        .setSocketTimeout(1000)
        .setConnectTimeout(1000)
        .build()

def executorService = Executors.newFixedThreadPool(MAX_THREADS)

tasks.each { task->
  def containers = task.containers
  containers.each { container->
    
    def containerInstance = containerInstanceMap.get(task.containerInstanceArn)
    def ec2Instance = ec2InstanceMap.get(containerInstance.ec2InstanceId)
    def containerDefinition = containerDefinitionMap.get(container.name)
    
    // Check if container definition contains the cfg handler url env
    def configHandlerPort = 80
    def configHandlerPath = null
    
    containerDefinition.environment.each { keyValuePair->
      if(keyValuePair.name.equals("ECS_CONFIG_HANDLER_PORT")) {
        try {
          configHandlerPort = Integer.parseInt(keyValuePair.value)
        } catch(Exception e) {
          LOG.error("Unable to parse ECS_CONFIG_HANDLER_PORT ${keyValuePair.value}")
        }
      }
      if(keyValuePair.name.equals("ECS_CONFIG_HANDLER_PATH")) {
        configHandlerPath = keyValuePair.value
      }
    }
    
    def running = container.lastStatus.equals("RUNNING")
    
    if(configHandlerPort != null && configHandlerPath != null && running) {
      
      def hostPort = null
      
      container.networkBindings.each { networkBinding->

        if(networkBinding.containerPort == configHandlerPort
            && networkBinding.protocol.equals("tcp")) {
          hostPort = networkBinding.hostPort
        }
      }
      
      def containerData = new HashMap()
      containerData.put("instance-id", ec2Instance.instanceId)
      containerData.put("private-ipv4", ec2Instance.privateIpAddress)
      containerData.put("public-ipv4", ec2Instance.publicIpAddress)
      containerData.put("container-arn", container.containerArn)
      containerData.put("container-instance-arn", containerInstance.containerInstanceArn)
      containerData.put("task-arn", task.taskArn)
      containerData.put("network-bindings", container.networkBindings)

      def jsonString = new JsonBuilder(containerData).toString()
      
      def configHandlerUrl = "http://${ec2Instance.privateIpAddress}:${hostPort}${configHandlerPath}"
      def httpPost = new HttpPost(configHandlerUrl)
      httpPost.setConfig(httpRequestConfig)
      httpPost.setEntity(new StringEntity(jsonString, ContentType.create("application/json")))
      
      executorService.execute(new Runnable() {
        public void run() {
          try {
            def response = httpClient.execute(httpPost)
            LOG.info("Posted data to endpoint: ${configHandlerUrl}")
            println configHandlerUrl
          } catch(Exception e) {
            LOG.error("Caught exception while posting data to endpoint ${configHandlerUrl}: ${e}")
          }
        }
      });
      
    }
  }
}

// Wait for the executor service to stop
executorService.shutdown()

def getContainerDefinitionsMap(def tasks, def taskDefinitionMap) {
  
  def containerDefinitions = new HashMap()
  
  tasks.each { task->
        
    def taskDefinition = taskDefinitionMap.get(task.taskDefinitionArn)
    def taskContainerDefinitions = taskDefinition.containerDefinitions
    taskContainerDefinitions.each { containerDefinition->
      containerDefinitions.put(containerDefinition.name, containerDefinition)
    }
  }
  
  return containerDefinitions
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
  
  // Put task definitions in a map <taskDefinitionArn, taskDefinition>
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

