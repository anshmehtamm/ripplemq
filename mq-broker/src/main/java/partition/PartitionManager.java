package partition;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.rpc.RpcServer;

import java.io.IOException;
import java.util.*;

import config.ClusterConfigManager;

import metadata.raft.TopicsRaftServer;
import partition.raft.PartitionRaftServer;

/**
 * PartitionManager handles Raft events such as leader changes,
 * membership changes, and topic list updates.
 */
public class PartitionManager {

  private TopicsRaftServer topicsRaftServer;
  private List<PeerId> previousMembers = new ArrayList<>();
  private PeerId selfPeerId;

  private RpcServer rpcServer;

  // Map of partition group IDs to PartitionRaftServer instances
  private Map<String, PartitionRaftServer> activePartitions = new HashMap<>();
  private int counter = 0;

  public PartitionManager(PeerId selfPeerId, RpcServer rpcServer) {
    this.selfPeerId = selfPeerId;
    this.rpcServer = rpcServer;
  }

  public void setTopicsRaftServer(TopicsRaftServer topicsRaftServer) {
    this.topicsRaftServer = topicsRaftServer;
  }

  /**
   * Handles leader change events.
   *
   * @param newLeader The new leader's PeerId. Null if there's no leader.
   */
  public void handleLeaderChange(PeerId newLeader) {
    System.err.println("PartitionManager: Handling leader change event.");
    if (newLeader != null && newLeader.equals(topicsRaftServer.getSelfPeerId())) {
      // We are the leader
      System.err.println("PartitionManager: New leader elected - " + newLeader);
      // Get current topics
      List<Topic> topics = topicsRaftServer.getStateMachine().getTopics();
      if (topics.isEmpty()) {
        ClusterConfigManager configManager = ClusterConfigManager.getInstance();
        topics = configManager.getClusterConfig().getTopics();
      }
      // Get current cluster members
      List<PeerId> peers = topicsRaftServer.getCurrentPeers();
      // Assign partitions
      if (peers.equals(previousMembers)) {
        System.err.println("PartitionManager: Cluster membership unchanged. Skipping partition reassignment.");
        return;
      }
      this.previousMembers = new ArrayList<>(peers);
      PartitionAssigner partitionAssigner = new PartitionAssigner();
      List<Topic> updatedTopics = partitionAssigner.assignPartitions(topics, peers);
      // Update topics via Raft
      topicsRaftServer.updateTopics(updatedTopics);
    } else {
      // We are not the leader
      System.err.println("PartitionManager: Not the leader.");
    }
  }

  /**
   * Handles membership change events.
   *
   * @param currentMembers The current list of cluster members.
   */
  public void handleMembershipChange(List<PeerId> currentMembers) {
    // Check if we are the leader
    Node node = topicsRaftServer.getNode();
    if (node != null && node.isLeader() && !currentMembers.equals(previousMembers)) {
      // Determine if a broker has died or a new broker has joined
      if (previousMembers != null) {
        System.err.println("PartitionManager: Cluster membership changed. Current members:");
        for (PeerId member : currentMembers) {
          System.err.println("\t" + member);
        }
        Set<PeerId> previousSet = new HashSet<>(previousMembers);
        Set<PeerId> currentSet = new HashSet<>(currentMembers);

        Set<PeerId> added = new HashSet<>(currentSet);
        added.removeAll(previousSet);

        Set<PeerId> removed = new HashSet<>(previousSet);
        removed.removeAll(currentSet);

        if (!added.isEmpty() || !removed.isEmpty()) {
          System.err.println("PartitionManager: Membership change detected.");
          // Reassign partitions
          List<Topic> topics = topicsRaftServer.getStateMachine().getTopics();
          if (topics.isEmpty()) {
            ClusterConfigManager configManager = ClusterConfigManager.getInstance();
            topics = configManager.getClusterConfig().getTopics();
          }
          PartitionAssigner partitionAssigner = new PartitionAssigner();
          List<Topic> updatedTopics = partitionAssigner.assignPartitions(topics, currentMembers);
          // Update topics via Raft
          topicsRaftServer.updateTopics(updatedTopics);
        }
      }
      previousMembers = new ArrayList<>(currentMembers);
    } else {
      // We are not the leader or membership hasn't changed
    }
  }

  /**
   * Handles topic list change events.
   *
   * @param newTopics The updated list of topics.
   */
  public void handleTopicListChange(List<Topic> newTopics) {
    System.err.println("PartitionManager: Topic list updated. Evaluating partitions to manage.");

    // Determine the partitions this node should handle based on the partition assignments
    Set<String> partitionsToHandle = new HashSet<>();
    Map<String, List<PeerId>> partitionPeerMap = new HashMap<>();

    for (Topic topic : newTopics) {
      if (topic.getPartitionAssignments() != null) {
        for (PartitionAssignment assignment : topic.getPartitionAssignments()) {
          String partitionGroupId = topic.getName() + "-" + assignment.getPartitionId();
          List<PeerId> partitionPeers = new ArrayList<>();
          for (String brokerPeerIdStr : assignment.getBrokerPeerIds()) {
            PeerId brokerPeerId = new PeerId();
            brokerPeerId.parse(brokerPeerIdStr);
            partitionPeers.add(brokerPeerId);
          }
          partitionPeerMap.put(partitionGroupId, partitionPeers);
          // Check if this node is assigned to this partition
          for (PeerId peer : partitionPeers) {
            if (peer.getEndpoint().equals(selfPeerId.getEndpoint())) {
              partitionsToHandle.add(partitionGroupId);
              break;
            }
          }
        }
      }
    }


    // Stop PartitionRaftServers that are no longer needed
    Set<String> partitionsToStop = new HashSet<>(activePartitions.keySet());
    partitionsToStop.removeAll(partitionsToHandle);
    for (String partitionGroupId : partitionsToStop) {
      // Stop PartitionRaftServer
      stopPartition(partitionGroupId);
    }

    // Start new PartitionRaftServers for new partitions
    for (String partitionGroupId : partitionsToHandle) {
      if (!activePartitions.containsKey(partitionGroupId)) {
        // Start PartitionRaftServer
        System.err.println("Starting PartitionRaftServer for partition " + partitionGroupId);
        try {
          startPartition(partitionGroupId, partitionPeerMap.get(partitionGroupId));
        } catch (IOException e) {
          e.printStackTrace();
          System.err.println("Failed to start PartitionRaftServer for partition " + partitionGroupId);
        }
      }
    }

  }

  /**
   * Starts a PartitionRaftServer for the given partition.
   *
   * @param partitionGroupId The group ID of the partition (format: topic_name:partitionId)
   * @param partitionPeers   The list of PeerIds in this partition's Raft group
   * @throws IOException If an I/O error occurs during setup
   */
  private void startPartition(String partitionGroupId, List<PeerId> partitionPeers) throws IOException {

    // Create and start PartitionRaftServer
    counter+=1;
    PartitionRaftServer partitionRaftServer = new PartitionRaftServer(partitionGroupId, selfPeerId, partitionPeers,
            this.rpcServer, this.counter);
    // Add to active partitions
    activePartitions.put(partitionGroupId, partitionRaftServer);
    partitionRaftServer.start();
    System.err.println("Started PartitionRaftServer for partition " + partitionGroupId);
  }

  /**
   * Stops the PartitionRaftServer for the given partition.
   *
   * @param partitionGroupId The group ID of the partition
   */
  private void stopPartition(String partitionGroupId) {
    PartitionRaftServer partitionRaftServer = activePartitions.remove(partitionGroupId);
    if (partitionRaftServer != null) {
      // Stop the PartitionRaftServer
      partitionRaftServer.shutdown();
      System.err.println("Stopped PartitionRaftServer for partition " + partitionGroupId);
    }
  }

  /**
   * Shuts down all active PartitionRaftServers.
   */
  public void shutdown() {
    for (String partitionGroupId : new HashSet<>(activePartitions.keySet())) {
      stopPartition(partitionGroupId);
    }
  }
}
