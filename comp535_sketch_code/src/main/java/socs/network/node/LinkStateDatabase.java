package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.PriorityQueue;

public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   */
  String getShortestPath(String destinationIP) {
	    HashMap<String, Integer> distance = new HashMap<>();//tracks the shortest distance to each node
	    HashMap<String, LinkedList<String>> paths = new HashMap<>();//tracks the path to each node
	    PriorityQueue<PathInfo> queue = new PriorityQueue<>(); //exploration queue
	    HashSet<String> visited = new HashSet<>();
	    
	    LinkedList<String> initialPath = new LinkedList<>();
	    initialPath.add(rd.simulatedIPAddress);
	    queue.add(new PathInfo(initialPath, 0));
	    distance.put(rd.simulatedIPAddress, 0);
	    paths.put(rd.simulatedIPAddress, initialPath);
	    
	    while (!queue.isEmpty()) {
	        PathInfo current = queue.poll();
	        String currentIP = current.path.getLast();
	        
	        if (visited.contains(currentIP)) continue;
	        
	        visited.add(currentIP);
	        
	        if (currentIP.equals(destinationIP)) {
	            return buildPathString(current.path);
	        }
	        
	        LSA currentLSA = _store.get(currentIP);
	        if (currentLSA == null || currentLSA.links == null) {
	            continue;
	        }
	        
	        for (LinkDescription link : currentLSA.links) {
	            if (visited.contains(link.linkID)) continue;
	            
	            int newDistance = current.cost + link.weight;
	            
	            //if we haven't seen this node, or if we found a shorter path
	            if (!distance.containsKey(link.linkID) || newDistance < distance.get(link.linkID)) {
	                distance.put(link.linkID, newDistance);
	                LinkedList<String> newPath = new LinkedList<>(current.path);
	                newPath.add(link.linkID);
	                paths.put(link.linkID, newPath);
	                queue.add(new PathInfo(newPath, newDistance));
	            }
	        }
	    }
	    return null; // No path found
	}
  
  //used to build output string for shortest path
  private String buildPathString(LinkedList<String> path) {
	  StringBuilder result = new StringBuilder();
	  result.append(path.poll());
	  
	  for (int i = 0; i < path.size(); i++) {
		  result.append(" -> ");
		  result.append(path.get(i));
	  }
	  return result.toString();
  }
  
  //used in shortest path to keep track of router sequence and cost of sequence
  private static class PathInfo implements Comparable<PathInfo>{
	  public int cost;
	  public LinkedList<String> path;
	  
	  public PathInfo(LinkedList<String> path, int cost) {
		  this.cost = cost;
		  this.path = path;
	  }
	
	  @Override
	  public int compareTo(PathInfo p) {
		  return Integer.compare(this.cost, p.cost);
	  }
  }

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    lsa.links.add(ld);
    return lsa;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {	
    	if(!lsa.deleted) {
    		sb.append(lsa.linkStateID).append(" (" + lsa.lsaSeqNumber + ")").append(":\n").append("-------------------------------\n");
    		for (LinkDescription ld : lsa.links) {
    			sb.append("\t\t"+ld.linkID).append(" | ").append(ld.portNum).append("\n");
    		}
    		sb.append("\n");
    	}
    }
    return sb.toString();
  }
}