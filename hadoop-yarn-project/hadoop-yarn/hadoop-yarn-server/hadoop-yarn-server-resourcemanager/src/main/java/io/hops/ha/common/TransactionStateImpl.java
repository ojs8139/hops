/*
 * Copyright (C) 2015 hops.io.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hops.ha.common;

import io.hops.StorageConnector;
import io.hops.common.GlobalThreadPool;
import io.hops.exception.StorageException;
import io.hops.metadata.util.RMStorageFactory;
import io.hops.metadata.util.RMUtilities;
import io.hops.metadata.yarn.dal.ContainerDataAccess;
import io.hops.metadata.yarn.dal.ContainerIdToCleanDataAccess;
import io.hops.metadata.yarn.dal.ContainerStatusDataAccess;
import io.hops.metadata.yarn.dal.FiCaSchedulerNodeDataAccess;
import io.hops.metadata.yarn.dal.FinishedApplicationsDataAccess;
import io.hops.metadata.yarn.dal.JustLaunchedContainersDataAccess;
import io.hops.metadata.yarn.dal.LaunchedContainersDataAccess;
import io.hops.metadata.yarn.dal.NodeDataAccess;
import io.hops.metadata.yarn.dal.NodeHBResponseDataAccess;
import io.hops.metadata.yarn.dal.PendingEventDataAccess;
import io.hops.metadata.yarn.dal.QueueMetricsDataAccess;
import io.hops.metadata.yarn.dal.RMContainerDataAccess;
import io.hops.metadata.yarn.dal.RMContextInactiveNodesDataAccess;
import io.hops.metadata.yarn.dal.RMNodeDataAccess;
import io.hops.metadata.yarn.dal.ResourceDataAccess;
import io.hops.metadata.yarn.dal.UpdatedContainerInfoDataAccess;
import io.hops.metadata.yarn.dal.fair.FSSchedulerNodeDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.AllocateResponseDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.AllocatedContainersDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.ApplicationAttemptStateDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.ApplicationStateDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.CompletedContainersStatusDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.RanNodeDataAccess;
import io.hops.metadata.yarn.dal.rmstatestore.UpdatedNodeDataAccess;
import io.hops.metadata.yarn.entity.Container;
import io.hops.metadata.yarn.entity.FiCaSchedulerNode;
import io.hops.metadata.yarn.entity.FiCaSchedulerNodeInfos;
import io.hops.metadata.yarn.entity.LaunchedContainers;
import io.hops.metadata.yarn.entity.PendingEvent;
import io.hops.metadata.yarn.entity.RMContainer;
import io.hops.metadata.yarn.entity.RMNode;
import io.hops.metadata.yarn.entity.RMNodeToAdd;
import io.hops.metadata.yarn.entity.Resource;
import io.hops.metadata.yarn.entity.rmstatestore.AllocateResponse;
import io.hops.metadata.yarn.entity.rmstatestore.ApplicationAttemptState;
import io.hops.metadata.yarn.entity.rmstatestore.ApplicationState;
import io.hops.metadata.yarn.entity.rmstatestore.UpdatedNode;
import io.hops.metadata.yarn.entity.rmstatestore.RanNode;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.AllocateResponsePBImpl;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.server.resourcemanager.ApplicationMasterService.AllocateResponseLock;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationAttemptStateDataPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationStateDataPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.impl.pb.ContainerPBImpl;
import org.apache.hadoop.yarn.api.records.impl.pb.ContainerStatusPBImpl;

import static org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.LOG;

public class TransactionStateImpl extends TransactionState {

  //Type of TransactionImpl to know which finishRPC to call
  //In future implementation this will be removed as a single finishRPC will exist
  private final TransactionType type;
  //NODE
  private Map<String, RMNode>
      rmNodesToUpdate = new ConcurrentHashMap<String, RMNode>();
  private final Map<NodeId, RMNodeInfo> rmNodeInfos =
      new ConcurrentSkipListMap<NodeId, RMNodeInfo>();
  private final Map<String, FiCaSchedulerNodeInfoToUpdate>
      ficaSchedulerNodeInfoToUpdate =
      new ConcurrentHashMap<String, FiCaSchedulerNodeInfoToUpdate>();
  private final Map<String, FiCaSchedulerNodeInfos>
      ficaSchedulerNodeInfoToAdd =
      new ConcurrentHashMap<String, FiCaSchedulerNodeInfos>();
  private final Map<String, FiCaSchedulerNodeInfos>
      ficaSchedulerNodeInfoToRemove =
      new ConcurrentHashMap<String, FiCaSchedulerNodeInfos>();
  private final FairSchedulerNodeInfo fairschedulerNodeInfo =
      new FairSchedulerNodeInfo();
  private final Map<String, RMContainer> rmContainersToUpdate =
      new ConcurrentHashMap<String, RMContainer>();
  private final Map<String, RMContainer> rmContainersToRemove =
      new ConcurrentHashMap<String, RMContainer>();
  private final Map<String, Container> toAddContainers =
          new HashMap<String, Container>();
  private final Map<String, Container> toUpdateContainers =
          new HashMap<String, Container>();
  private final Map<String, Container> toRemoveContainers =
          new HashMap<String, Container>();
  private final CSQueueInfo csQueueInfo = new CSQueueInfo();
  
  //APP
  private final SchedulerApplicationInfo schedulerApplicationInfo;
  private final Map<ApplicationId, ApplicationState> applicationsToAdd = new ConcurrentHashMap<ApplicationId, ApplicationState>();
  private final Map<ApplicationId, List<UpdatedNode>> updatedNodeIdToAdd = new ConcurrentHashMap<ApplicationId, List<UpdatedNode>>();
  private final Queue<ApplicationId> applicationsStateToRemove =
      new ConcurrentLinkedQueue<ApplicationId>();
  private final Map<String, ApplicationAttemptState> appAttempts =
      new ConcurrentHashMap<String, ApplicationAttemptState>();
  private final Map<ApplicationAttemptId, Map<Integer, RanNode>>ranNodeToAdd =
          new ConcurrentHashMap<ApplicationAttemptId, Map<Integer, RanNode>>();
  private final Map<ApplicationAttemptId, AllocateResponse>
      allocateResponsesToAdd =
      new ConcurrentHashMap<ApplicationAttemptId, AllocateResponse>();
  private final Map<ApplicationAttemptId, AllocateResponse> allocateResponsesToRemove =
      new ConcurrentHashMap<ApplicationAttemptId, AllocateResponse>();
  
  private final Map<String, Queue<byte[]>> justFinishedContainerToAdd = 
          new ConcurrentHashMap<String, Queue<byte[]>>();
  private final Map<String, Queue<byte[]>> justFinishedContainerToRemove = 
          new ConcurrentHashMap<String, Queue<byte[]>>();
  
  
  //COMTEXT
  private final RMContextInfo rmcontextInfo = new RMContextInfo();
  
  
  

  //PersistedEvent to persist for distributed RT
  private final Queue<PendingEvent> pendingEventsToAdd =
      new ConcurrentLinkedQueue<PendingEvent>();
  private final Queue<PendingEvent> persistedEventsToRemove =
      new ConcurrentLinkedQueue<PendingEvent>();

  //for debug and evaluation
  String rpcType = null;
  NodeId nodeId = null;
  private TransactionStateManager manager =null;
  
   public TransactionStateImpl(TransactionType type) {
    super(1, false);
    this.type = type;
    this.schedulerApplicationInfo =
      new SchedulerApplicationInfo(this);
    manager = new TransactionStateManager();
  }
   
  public TransactionStateImpl(TransactionType type, int initialCounter,
          boolean batch, TransactionStateManager manager) {
    super(initialCounter, batch);
    this.type = type;
    this.schedulerApplicationInfo =
      new SchedulerApplicationInfo(this);
    this.manager = manager;
  }

  
  @Override
  public void commit(boolean first) throws IOException {
    if(first){
      RMUtilities.putTransactionStateInQueues(this, rmNodesToUpdate.keySet(), 
              appIds);
      RMUtilities.logPutInCommitingQueue(this);
    }
    GlobalThreadPool.getExecutorService().execute(new RPCFinisher(this));
  }

  public FairSchedulerNodeInfo getFairschedulerNodeInfo() {
    return fairschedulerNodeInfo;
  }

  public void persistFairSchedulerNodeInfo(FSSchedulerNodeDataAccess FSSNodeDA)
      throws StorageException {
    fairschedulerNodeInfo.persist(FSSNodeDA);
  }

  public static int callsGetSchedulerApplicationInfos = 0;
  public SchedulerApplicationInfo getSchedulerApplicationInfos(ApplicationId appId) {
    if(!addAppId(appId)){
      callsGetSchedulerApplicationInfos++;
    }
    return schedulerApplicationInfo;
  }

  
  public boolean addAppId(ApplicationId appId){
    return appIds.add(appId);
  }
    
  public void persist() throws IOException {
    persitApplicationToAdd();
    persistApplicationStateToRemove();
    persistAppAttempt();
    persistRandNode();
    persistAllocateResponsesToAdd();
    persistAllocateResponsesToRemove();
    persistRMContainerToUpdate();
    persistRMContainersToRemove();
    persistContainersToAdd();
    persistContainersToRemove();
    //TODO rebuild cluster resource from node resources
//    persistClusterResourceToUpdate();
//    persistUsedResourceToUpdate();
  }

  public void persistSchedulerApplicationInfo(QueueMetricsDataAccess QMDA, StorageConnector connector)
      throws StorageException {
      schedulerApplicationInfo.persist(QMDA, connector);
  }

  public CSQueueInfo getCSQueueInfo() {
    return csQueueInfo;
  }

  public void persistCSQueueInfo(
          StorageConnector connector)
          throws StorageException {

      csQueueInfo.persist(connector);
  }
  
  public FiCaSchedulerNodeInfoToUpdate getFicaSchedulerNodeInfoToUpdate(
      String nodeId) {
    FiCaSchedulerNodeInfoToUpdate nodeInfo =
        ficaSchedulerNodeInfoToUpdate.get(nodeId);
    if (nodeInfo == null) {
      nodeInfo = new FiCaSchedulerNodeInfoToUpdate(nodeId, this);
      ficaSchedulerNodeInfoToUpdate.put(nodeId, nodeInfo);
    }
    return nodeInfo;
  }
  
  public void addFicaSchedulerNodeInfoToAdd(String nodeId,
          org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode node) {

    FiCaSchedulerNodeInfos nodeInfos = new FiCaSchedulerNodeInfos();
    String reservedContainer = node.getReservedContainer() != null ? node.
            getReservedContainer().toString() : null;
    nodeInfos.setFiCaSchedulerNode(
            new FiCaSchedulerNode(nodeId, node.getNodeName(),
                    node.getNumContainers(), reservedContainer));
    //Add Resources
    if (node.getTotalResource() != null) {
      nodeInfos.setTotalResource(new Resource(nodeId, Resource.TOTAL_CAPABILITY,
              Resource.FICASCHEDULERNODE, node.getTotalResource().getMemory(),
              node.getTotalResource().getVirtualCores(),0));
    }
    if (node.getAvailableResource() != null) {
      nodeInfos.setAvailableResource(new Resource(nodeId, Resource.AVAILABLE,
              Resource.FICASCHEDULERNODE,
              node.getAvailableResource().getMemory(),
              node.getAvailableResource().getVirtualCores(),0));
    }
    if (node.getUsedResource() != null) {
      nodeInfos.setUsedResource(
              new Resource(nodeId, Resource.USED, Resource.FICASCHEDULERNODE,
                      node.getUsedResource().getMemory(),
                      node.getUsedResource().getVirtualCores(),0));
    }
    if (node.getReservedContainer() != null) {
      addRMContainerToUpdate((RMContainerImpl)node.getReservedContainer());
    }
    
    //Add launched containers
    if (node.getRunningContainers() != null) {
      for (org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer
              : node
              .getRunningContainers()) {
        nodeInfos.addLaunchedContainer(
                new LaunchedContainers(node.getNodeID().toString(),
                        rmContainer.getContainerId().toString(),
                        rmContainer.getContainerId().toString()));
      }
    }

    ficaSchedulerNodeInfoToAdd.put(nodeId, nodeInfos);
    ficaSchedulerNodeInfoToRemove.remove(nodeId);
    ficaSchedulerNodeInfoToUpdate.remove(nodeId);
  }
  
  public void addFicaSchedulerNodeInfoToRemove(String nodeId,
          org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerNode node) {
    if (ficaSchedulerNodeInfoToAdd.remove(nodeId) == null) {
      FiCaSchedulerNodeInfos nodeInfo = new FiCaSchedulerNodeInfos();
      ficaSchedulerNodeInfoToRemove.put(nodeId, nodeInfo);
    }
    ficaSchedulerNodeInfoToUpdate.remove(nodeId);
    //TORECOVER shouldn't we take care of the reserved rmcontainer?
  }
  
  static public int callsAddApplicationToAdd =0;
  public void addApplicationToAdd(RMAppImpl app) {
    
    ApplicationStateDataPBImpl appStateData =
              (ApplicationStateDataPBImpl) ApplicationStateDataPBImpl
                  .newApplicationStateData(app.getSubmitTime(),
                      app.getStartTime(), app.getUser(),
                      app.getApplicationSubmissionContext(), 
                      app.getState(), 
                      app.getDiagnostics().toString(),
                      app.getFinishTime(), 
                      null);
          byte[] appStateDataBytes = appStateData.getProto().toByteArray();
          ApplicationState hop =
              new ApplicationState(app.getApplicationId().toString(),
                  appStateDataBytes, app.getUser(), app.getName(),
                  app.getState().toString());
    applicationsToAdd.put(app.getApplicationId(), hop);
    List<UpdatedNode> nodeIdsToAdd = new ArrayList<UpdatedNode>();
    for(NodeId nid: app.getUpdatedNodesId()){
      UpdatedNode node = new UpdatedNode(app.getApplicationId().toString(),
               nid.toString());
      nodeIdsToAdd.add(node);
    }
    updatedNodeIdToAdd.put(app.getApplicationId(), nodeIdsToAdd);
    applicationsStateToRemove.remove(app.getApplicationId());
    callsAddApplicationToAdd++;
    addAppId(app.getApplicationId());
  }
  
  private void persitApplicationToAdd() throws IOException {
    if (!applicationsToAdd.isEmpty()) {
      ApplicationStateDataAccess DA =
          (ApplicationStateDataAccess) RMStorageFactory
              .getDataAccess(ApplicationStateDataAccess.class);
      DA.addAll(applicationsToAdd.values());
      UpdatedNodeDataAccess uNDA = (UpdatedNodeDataAccess)RMStorageFactory
              .getDataAccess(UpdatedNodeDataAccess.class);
      uNDA.addAll(updatedNodeIdToAdd.values());
    }
  }
  
  public static int callsAddApplicationStateToRemove = 0;
  public void addApplicationStateToRemove(ApplicationId appId) {
    if(applicationsToAdd.remove(appId)==null){
    updatedNodeIdToAdd.remove(appId);
    applicationsStateToRemove.add(appId);
    }
    callsAddApplicationStateToRemove++;
    addAppId(appId);
  }

  private void persistApplicationStateToRemove() throws StorageException {
    if (!applicationsStateToRemove.isEmpty()) {
      ApplicationStateDataAccess DA =
          (ApplicationStateDataAccess) RMStorageFactory
              .getDataAccess(ApplicationStateDataAccess.class);
      Queue<ApplicationState> appToRemove = new ConcurrentLinkedQueue<ApplicationState>();
      for (ApplicationId appId : applicationsStateToRemove) {
        appToRemove.add(new ApplicationState(appId.toString()));
      }
      DA.removeAll(appToRemove);
      //TODO remove appattempts
    }
  }
  
  public void addAppAttempt(RMAppAttempt appAttempt) {
    String appIdStr = appAttempt.getAppAttemptId().getApplicationId().
            toString();

    Credentials credentials = appAttempt.getCredentials();
    ByteBuffer appAttemptTokens = null;

    if (credentials != null) {
      DataOutputBuffer dob = new DataOutputBuffer();
      try {
        credentials.writeTokenStorageToStream(dob);
      } catch (IOException ex) {
        LOG.error("faillerd to persist tocken: " + ex, ex);
      }
      appAttemptTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
    }
    
    ApplicationAttemptStateDataPBImpl attemptStateData
            = (ApplicationAttemptStateDataPBImpl) ApplicationAttemptStateDataPBImpl.
            newApplicationAttemptStateData(appAttempt.getAppAttemptId(),
                    appAttempt.getMasterContainer(), appAttemptTokens,
                    appAttempt.getStartTime(), appAttempt.
                    getState(), appAttempt.getOriginalTrackingUrl(),
                    appAttempt.getDiagnostics(),
                    appAttempt.getFinalApplicationStatus(),
                    new HashSet<NodeId>(),
                    new ArrayList<ContainerStatus>(),
                    appAttempt.getProgress(), appAttempt.getHost(),
                    appAttempt.getRpcPort());

    byte[] attemptStateByteArray = attemptStateData.getProto().toByteArray();
    if(attemptStateByteArray.length > 13000){
      LOG.error("application Attempt State too big: " + appAttempt.getAppAttemptId() + " " + appAttemptTokens.array().length + " " + 
              appAttempt.getDiagnostics().getBytes().length + " " + appAttempt.getRanNodes().size() + " " + appAttempt.getJustFinishedContainers().size() +
              " ");
    }
    this.appAttempts.put(appAttempt.getAppAttemptId().toString(),
            new ApplicationAttemptState(appIdStr, appAttempt.getAppAttemptId().
                    toString(),
                    attemptStateByteArray, appAttempt.
                    getHost(), appAttempt.getRpcPort(), appAttemptTokens,
                    appAttempt.
                    getTrackingUrl()));
    addAppId(appAttempt.getAppAttemptId().getApplicationId());
  }
  
  
  public void addAllJustFinishedContainersToAdd(List<ContainerStatus> status, ApplicationAttemptId appAttemptId){
    Queue<byte[]> toAdd = justFinishedContainerToAdd.get(appAttemptId.toString());
    if(toAdd == null){
      toAdd = new ConcurrentLinkedQueue<byte[]>();
      justFinishedContainerToAdd.put(appAttemptId.toString(), toAdd);
    }
    for(ContainerStatus container: status){
      toAdd.add(((ContainerStatusPBImpl)container).getProto().toByteArray());
    }
  }
  
  public void addJustFinishedContainerToAdd(ContainerStatus status, ApplicationAttemptId appAttemptId){
    Queue<byte[]> toAdd = justFinishedContainerToAdd.get(appAttemptId.toString());
    if(toAdd == null){
      toAdd = new ConcurrentLinkedQueue<byte[]>();
      justFinishedContainerToAdd.put(appAttemptId.toString(), toAdd);
    }
    toAdd.add(((ContainerStatusPBImpl)status).getProto().toByteArray());
  }
  
    public void addAllJustFinishedContainersToRemove(List<ContainerStatus> status, ApplicationAttemptId appAttemptId){
    Queue<byte[]> toRemove = justFinishedContainerToRemove.get(appAttemptId.toString());
    if(toRemove == null){
      toRemove = new ConcurrentLinkedQueue<byte[]>();
      justFinishedContainerToRemove.put(appAttemptId.toString(), toRemove);
    }
    for(ContainerStatus container: status){
      toRemove.add(((ContainerStatusPBImpl)container).getProto().toByteArray());
    }
  }
  
  public void addAllRanNodes(RMAppAttempt appAttempt) {
    Map<Integer, RanNode> ranNodeToPersist = new HashMap<Integer, RanNode>();
    Queue<NodeId> ranNodes = new ConcurrentLinkedQueue<NodeId>(appAttempt.getRanNodes());
    for (NodeId nid : ranNodes) {
      RanNode node = new RanNode(appAttempt.getAppAttemptId().toString(),
                      nid.toString());
      ranNodeToPersist.put(node.hashCode(),node);
    }

    this.ranNodeToAdd.put(appAttempt.getAppAttemptId(),
            ranNodeToPersist);
  }

  public void addRanNode(NodeId nid, ApplicationAttemptId appAttemptId) {
    if(!this.ranNodeToAdd.containsKey(appAttemptId)){
      this.ranNodeToAdd.put(appAttemptId, new HashMap<Integer,RanNode>());
    }
    RanNode node = new RanNode(appAttemptId.toString(), nid.toString());
    this.ranNodeToAdd.get(appAttemptId).put(node.hashCode(),node);
  }
  
  private void persistAppAttempt() throws IOException {
    if (!appAttempts.isEmpty()) {

      ApplicationAttemptStateDataAccess DA =
          (ApplicationAttemptStateDataAccess) RMStorageFactory.
              getDataAccess(ApplicationAttemptStateDataAccess.class);
      DA.addAll(appAttempts.values());
    }
  }
  
  private void persistRandNode() throws IOException {
    if(!ranNodeToAdd.isEmpty()){
        RanNodeDataAccess rDA= (RanNodeDataAccess) RMStorageFactory.
            getDataAccess(RanNodeDataAccess.class);
      rDA.addAll(ranNodeToAdd.values());
    }
  }
  
  
  public void addAllocateResponse(ApplicationAttemptId id,
          AllocateResponseLock allocateResponse) {
    AllocateResponsePBImpl lastResponse
            = (AllocateResponsePBImpl) allocateResponse.
            getAllocateResponse();
    if (lastResponse != null) {
      List<String> allocatedContainers = new ArrayList<String>();
      for(org.apache.hadoop.yarn.api.records.Container container:
              lastResponse.getAllocatedContainers()){
        allocatedContainers.add(container.getId().toString());
      }
      
      Map<String, byte[]> completedContainersStatuses = new HashMap<String, byte[]>();
      for(ContainerStatus status: lastResponse.getCompletedContainersStatuses()){
        completedContainersStatuses.put(status.getContainerId().toString(), ((ContainerStatusPBImpl)status).getProto().toByteArray());
      }
      
      AllocateResponsePBImpl toPersist = new AllocateResponsePBImpl();
      toPersist.setAMCommand(lastResponse.getAMCommand());
      toPersist.setAvailableResources(lastResponse.getAvailableResources());
      toPersist.setDecreasedContainers(lastResponse.getDecreasedContainers());
      toPersist.setIncreasedContainers(lastResponse.getIncreasedContainers());
      toPersist.setNumClusterNodes(lastResponse.getNumClusterNodes());
      toPersist.setPreemptionMessage(lastResponse.getPreemptionMessage());
      toPersist.setResponseId(lastResponse.getResponseId());
      toPersist.setUpdatedNodes(lastResponse.getUpdatedNodes());
      
      this.allocateResponsesToAdd.put(id, new AllocateResponse(id.toString(),
              toPersist.getProto().toByteArray(), allocatedContainers, 
      allocateResponse.getAllocateResponse().getResponseId(), completedContainersStatuses));
      if(toPersist.getProto().toByteArray().length>1000){
        LOG.debug("add allocateResponse of size " + toPersist.getProto().toByteArray().length + 
                " for " + id + " content: " + print(toPersist));
      }
      allocateResponsesToRemove.remove(id);
      addAppId(id.getApplicationId());
    }
  }
  
  private String print(org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse response){
    String s ="";
    if(response.getAMCommand()!= null)
      s = s + "AM comande : " + response.getAMCommand().toString();
    if(response.getAllocatedContainers()!=null)
    s = s + " allocated containers size " + response.getAllocatedContainers().size();
    if(response.getCompletedContainersStatuses()!=null)
    s = s + " completed containersStatuses size " + response.getCompletedContainersStatuses().size();
    if(response.getDecreasedContainers()!=null)
    s = s + " decreasedcont: " + response.getDecreasedContainers().size();
    if(response.getIncreasedContainers()!= null)
    s = s + " increased containers: " + response.getIncreasedContainers().size();
    if(response.getNMTokens()!= null)
    s = s + " nmtokens " + response.getNMTokens().size();
    if(response.getUpdatedNodes()!=null)
    s =s + " updatedNodes " + response.getUpdatedNodes().size();
    return s;
  }

  private void persistAllocateResponsesToAdd() throws IOException {
    if (!allocateResponsesToAdd.isEmpty()) {
      AllocateResponseDataAccess da =
          (AllocateResponseDataAccess) RMStorageFactory
              .getDataAccess(AllocateResponseDataAccess.class);
      AllocatedContainersDataAccess containersDA = (AllocatedContainersDataAccess)
              RMStorageFactory.getDataAccess(AllocatedContainersDataAccess.class);
      da.update(allocateResponsesToAdd.values());
      containersDA.update(allocateResponsesToAdd.values());
      CompletedContainersStatusDataAccess completedContainersDA =
              (CompletedContainersStatusDataAccess) 
              RMStorageFactory.getDataAccess(CompletedContainersStatusDataAccess.class);
      completedContainersDA.update(allocateResponsesToAdd.values());
    }
  }
  
  public void removeAllocateResponse(ApplicationAttemptId id, int responseId) {
    if(allocateResponsesToAdd.remove(id)==null){
    this.allocateResponsesToRemove.put(id,new AllocateResponse(id.toString(), responseId));
    }
    addAppId(id.getApplicationId());
  }
  
  private void persistAllocateResponsesToRemove() throws IOException {
    if (!allocateResponsesToRemove.isEmpty()) {
      AllocateResponseDataAccess da =
          (AllocateResponseDataAccess) RMStorageFactory
              .getDataAccess(AllocateResponseDataAccess.class);

      da.removeAll(allocateResponsesToRemove.values());
    }
  }
  
   private byte[] getRMContainerBytes(org.apache.hadoop.yarn.api.records.Container Container){
    if(Container instanceof ContainerPBImpl){
      return ((ContainerPBImpl) Container).getProto()
            .toByteArray();
    }else{
      return new byte[0];
    }
  }
   
  public void addRMContainerToAdd(RMContainerImpl rmContainer) {
    rmContainersToRemove.remove(rmContainer.getContainerId().toString());
    addRMContainerToUpdate(rmContainer);
    io.hops.metadata.yarn.entity.Container hopContainer
            = new Container(rmContainer.
                    getContainerId().toString(),
                    getRMContainerBytes(rmContainer.getContainer()));
    toAddContainers.put(hopContainer.getContainerId(),hopContainer);
    appIds.add(rmContainer.getApplicationAttemptId().getApplicationId());
    nodesIds.add(rmContainer.getNodeId());
  }
  
  protected void persistContainersToAdd() throws StorageException {
    if (!toAddContainers.isEmpty()) {
      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory.
              getDataAccess(ContainerDataAccess.class);
      cDA.addAll(toAddContainers.values());
    }
    if (!toUpdateContainers.isEmpty()) {
      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory.
              getDataAccess(ContainerDataAccess.class);
      cDA.addAll(toUpdateContainers.values());
    }
  }
  
  public void addContainerToUpdate(
          org.apache.hadoop.yarn.api.records.Container container,
          ApplicationId appId){
      io.hops.metadata.yarn.entity.Container hopContainer
            = new Container(container.getId().toString(),
                    getRMContainerBytes(container));
    toUpdateContainers.put(hopContainer.getContainerId(),hopContainer);
    appIds.add(appId);
    nodesIds.add(container.getNodeId());
  }
  
  
  public synchronized void addRMContainerToRemove(
          org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer rmContainer) {
    rmContainersToUpdate.remove(rmContainer.getContainerId().toString());
    toUpdateContainers.remove(rmContainer.getContainerId().toString());
    if(toAddContainers.remove(rmContainer.getContainerId().toString())==null){
      rmContainersToRemove.put(rmContainer.getContainerId().toString(),
              new RMContainer(rmContainer.getContainerId().toString(),
              rmContainer.getApplicationAttemptId().toString()));
      toRemoveContainers.put(rmContainer.getContainerId().toString(),
              new Container(rmContainer.
                  getContainerId().toString()));
      appIds.add(rmContainer.getApplicationAttemptId().getApplicationId());
      nodesIds.add(rmContainer.getNodeId());
    }
  }
  
  protected void persistContainersToRemove() throws StorageException {
    if (!toRemoveContainers.isEmpty()) {
      ContainerDataAccess cDA = (ContainerDataAccess) RMStorageFactory.
              getDataAccess(ContainerDataAccess.class);
      cDA.removeAll(toRemoveContainers.values());
    }
  }
  protected void persistRMContainersToRemove() throws StorageException {
      if (!rmContainersToRemove.isEmpty()) {
          RMContainerDataAccess rmcontainerDA
                  = (RMContainerDataAccess) RMStorageFactory
                  .getDataAccess(RMContainerDataAccess.class);            
          rmcontainerDA.removeAll(rmContainersToRemove.values());
      }
  }
    
  public synchronized void addRMContainerToUpdate(RMContainerImpl rmContainer) {
    if (rmContainersToRemove.containsKey(
            rmContainer.getContainerId().toString())) {
        return;
    }
            
    boolean isReserved = (rmContainer.getReservedNode() != null)
            && (rmContainer.getReservedPriority() != null);

    String reservedNode = isReserved ? rmContainer.getReservedNode().toString()
            : null;
    int reservedPriority = isReserved ? rmContainer.getReservedPriority().
            getPriority() : 0;
    int reservedMemory = isReserved ? rmContainer.getReservedResource().
            getMemory() : 0;

    int reservedVCores = isReserved ? rmContainer.getReservedResource().
            getVirtualCores() : 0;
    
    rmContainersToUpdate
            .put(rmContainer.getContainer().getId().toString(), new RMContainer(
                            rmContainer.getContainer().getId().toString(),
                            rmContainer.getApplicationAttemptId().toString(),
                            rmContainer.getNodeId().toString(),
                            rmContainer.getUser(),
                            reservedNode,
                            reservedPriority,
                            reservedMemory,
                            reservedVCores,
                            rmContainer.getStartTime(),
                            rmContainer.getFinishTime(),
                            rmContainer.getState().toString(),
                            rmContainer.getContainerState().toString(),
                            rmContainer.getContainerExitStatus()));
    appIds.add(rmContainer.getApplicationAttemptId().getApplicationId());
    nodesIds.add(rmContainer.getNodeId());
  }

  private void persistRMContainerToUpdate() throws StorageException {
    if (!rmContainersToUpdate.isEmpty()) {
      RMContainerDataAccess rmcontainerDA =
          (RMContainerDataAccess) RMStorageFactory
              .getDataAccess(RMContainerDataAccess.class);      
      rmcontainerDA.addAll(rmContainersToUpdate.values());
    }
  }
  
  public void persistFicaSchedulerNodeInfo(ResourceDataAccess resourceDA,
      FiCaSchedulerNodeDataAccess ficaNodeDA,
      RMContainerDataAccess rmcontainerDA,
      LaunchedContainersDataAccess launchedContainersDA)
      throws StorageException {
    persistFiCaSchedulerNodeToAdd(resourceDA, ficaNodeDA, rmcontainerDA,
        launchedContainersDA);
    FiCaSchedulerNodeInfoAgregate agregate = new FiCaSchedulerNodeInfoAgregate();
    for (FiCaSchedulerNodeInfoToUpdate nodeInfo : ficaSchedulerNodeInfoToUpdate
        .values()) {
      nodeInfo.agregate(agregate);
    }
    agregate.persist(resourceDA, ficaNodeDA, rmcontainerDA, launchedContainersDA);
    persistFiCaSchedulerNodeToRemove(resourceDA, ficaNodeDA, rmcontainerDA, launchedContainersDA);
  }

  public RMContextInfo getRMContextInfo() {
    return rmcontextInfo;
  }

  public void persistRmcontextInfo(RMNodeDataAccess rmnodeDA,
      ResourceDataAccess resourceDA, NodeDataAccess nodeDA,
      RMContextInactiveNodesDataAccess rmctxinactivenodesDA)
      throws StorageException {
    rmcontextInfo.persist(rmnodeDA, resourceDA, nodeDA, rmctxinactivenodesDA);
  }


  public void persistRMNodeToUpdate(RMNodeDataAccess rmnodeDA)
      throws StorageException {
    if (!rmNodesToUpdate.isEmpty()) {
      rmnodeDA.addAll(new ArrayList(rmNodesToUpdate.values()));
    }
  }

  public void toUpdateRMNode(
          org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode rmnodeToAdd) {
    int pendingEventId = getRMNodeInfo(rmnodeToAdd.getNodeID()).getPendingId();
    RMNode hopRMNode = new RMNode(rmnodeToAdd.getNodeID().toString(),
            rmnodeToAdd.getHostName(), rmnodeToAdd.getCommandPort(),
            rmnodeToAdd.getHttpPort(), rmnodeToAdd.getNodeAddress(),
            rmnodeToAdd.getHttpAddress(), rmnodeToAdd.getHealthReport(),
            rmnodeToAdd.getLastHealthReportTime(),
            ((RMNodeImpl) rmnodeToAdd).getCurrentState(),
            rmnodeToAdd.getNodeManagerVersion(), -1,
            ((RMNodeImpl) rmnodeToAdd).getUpdatedContainerInfoId(),
            pendingEventId);
    
    RMNodeToAdd hopRMNodeToAdd = getRMContextInfo().getToAddActiveRMNode(rmnodeToAdd.getNodeID());
    if(hopRMNodeToAdd==null){
      nodesIds.add(rmnodeToAdd.getNodeID());
      this.rmNodesToUpdate.put(rmnodeToAdd.getNodeID().toString(),
              hopRMNode);
    } else {
      hopRMNodeToAdd.setRMNode(hopRMNode);
    }
  }

  public Map<String, RMNode> getRMNodesToUpdate(){
    return rmNodesToUpdate;
  }
  
  public RMNodeInfo getRMNodeInfo(NodeId rmNodeId) {
    RMNodeInfo result = rmNodeInfos.get(rmNodeId);
    if (result == null) {
      result = new RMNodeInfo(rmNodeId.toString());
      rmNodeInfos.put(rmNodeId, result);
    }
    return result;
  }

  public void persistRMNodeInfo(NodeHBResponseDataAccess hbDA,
      ContainerIdToCleanDataAccess cidToCleanDA,
      JustLaunchedContainersDataAccess justLaunchedContainersDA,
          UpdatedContainerInfoDataAccess updatedContainerInfoDA,
          FinishedApplicationsDataAccess faDA, ContainerStatusDataAccess csDA,
          PendingEventDataAccess persistedEventsDA, StorageConnector connector)
          throws StorageException {
    if (rmNodeInfos != null) {
      RMNodeInfoAgregate agregate = new RMNodeInfoAgregate();
      for (RMNodeInfo rmNodeInfo : rmNodeInfos.values()) {
        rmNodeInfo.agregate(agregate);
      }
      agregate.persist(hbDA, cidToCleanDA, justLaunchedContainersDA,
              updatedContainerInfoDA, faDA, csDA,persistedEventsDA, connector);
    }
  }

  
  private void persistFiCaSchedulerNodeToRemove(ResourceDataAccess resourceDA, 
          FiCaSchedulerNodeDataAccess ficaNodeDA, RMContainerDataAccess rmcontainerDA, 
          LaunchedContainersDataAccess launchedContainersDA) throws StorageException {
    if (!ficaSchedulerNodeInfoToRemove.isEmpty()) {
      Queue<FiCaSchedulerNode> toRemoveFiCaSchedulerNodes =
          new ConcurrentLinkedQueue<FiCaSchedulerNode>();
      for (String nodeId : ficaSchedulerNodeInfoToRemove.keySet()) {
        toRemoveFiCaSchedulerNodes.add(new FiCaSchedulerNode(nodeId));
      }
      ficaNodeDA.removeAll(toRemoveFiCaSchedulerNodes);
    }
  }

  public void persistFiCaSchedulerNodeToAdd(ResourceDataAccess resourceDA,
          FiCaSchedulerNodeDataAccess ficaNodeDA,
          RMContainerDataAccess rmcontainerDA,
          LaunchedContainersDataAccess launchedContainersDA)
          throws StorageException {
    if (!ficaSchedulerNodeInfoToAdd.isEmpty()) {
      ArrayList<FiCaSchedulerNode> toAddFiCaSchedulerNodes
              = new ArrayList<FiCaSchedulerNode>();
      ArrayList<Resource> toAddResources = new ArrayList<Resource>();
      ArrayList<LaunchedContainers> toAddLaunchedContainers
              = new ArrayList<LaunchedContainers>();
      for (FiCaSchedulerNodeInfos nodeInfo : ficaSchedulerNodeInfoToAdd.values()) {

        toAddFiCaSchedulerNodes.add(nodeInfo.getFiCaSchedulerNode());
        //Add Resources
        if (nodeInfo.getTotalResource() != null) {
          toAddResources.add(nodeInfo.getTotalResource());
        }
        if (nodeInfo.getAvailableResource() != null) {
          toAddResources.add(nodeInfo.getAvailableResource());
        }
        if (nodeInfo.getUsedResource() != null) {
          toAddResources.add(nodeInfo.getUsedResource());
        }
        //Add launched containers
        if (nodeInfo.getLaunchedContainers() != null) {
          toAddLaunchedContainers.addAll(nodeInfo.getLaunchedContainers());
        }
      }
      resourceDA.addAll(toAddResources);
      ficaNodeDA.addAll(toAddFiCaSchedulerNodes);
      launchedContainersDA.addAll(toAddLaunchedContainers);
    }
  }

  /**
   * Remove pending event from DB. In this case, the event id is not needed,
   * hence set to MIN.
   * <p/>
   *
   * @param id
   * @param rmnodeId
   * @param type
   * @param status
   */
  public void addPendingEventToRemove(int id, String rmnodeId, int type,
      int status) {
    this.persistedEventsToRemove
        .add(new PendingEvent(rmnodeId, type, status, id));
  }


  
  private class RPCFinisher implements Runnable {

    private final TransactionStateImpl ts;

    public RPCFinisher(TransactionStateImpl ts) {
      this.ts = ts;
    }

    public void run() {
      try{
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY-1);
        RMUtilities.finishRPCs(ts);
      }catch(IOException ex){
        LOG.error("did not commit state properly", ex);
    }
  }
}  
  public TransactionStateManager getManager(){
    return manager;
  }
}
